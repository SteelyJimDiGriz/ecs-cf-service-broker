package com.emc.ecs.servicebroker.service;

import com.emc.ecs.servicebroker.EcsManagementClientException;
import com.emc.ecs.servicebroker.model.PlanProxy;
import com.emc.ecs.servicebroker.model.ServiceDefinitionProxy;
import com.emc.ecs.servicebroker.repository.LastOperationSerializer;
import com.emc.ecs.servicebroker.repository.ServiceInstance;
import com.emc.ecs.servicebroker.repository.ServiceInstanceRepository;
import com.emc.object.s3.S3Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.servicebroker.exception.ServiceBrokerException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceUpdateNotSupportedException;
import org.springframework.cloud.servicebroker.model.instance.*;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.lang.String.format;

@Service
public class EcsServiceInstanceService implements ServiceInstanceService {

    private static final Logger logger = LoggerFactory.getLogger(EcsServiceInstanceService.class);

    private static final String NO_SERVICE_MATCHING_TYPE = "No service matching type: ";
    private static final String NAMESPACE = "namespace";
    private static final String BUCKET = "bucket";
    private static final String SERVICE_TYPE = "service-type";
    private static final Logger LOG = LoggerFactory.getLogger(EcsServiceInstanceService.class);

    private static final LastOperationSerializer SUCCEEDED_OPERATION = new LastOperationSerializer(OperationState.SUCCEEDED, "", false);

    @Autowired
    private EcsService ecs;

    @Autowired
    private ServiceInstanceRepository repository;

    public EcsServiceInstanceService() {
        super();
    }

    EcsServiceInstanceService(EcsService ecs, ServiceInstanceRepository repo) {
        super();
        this.ecs = ecs;
        this.repository = repo;
    }

    @Override
    public Mono<CreateServiceInstanceResponse> createServiceInstance(CreateServiceInstanceRequest request) {
        String serviceInstanceId = request.getServiceInstanceId();
        String serviceDefinitionId = request.getServiceDefinitionId();
        String planId = request.getPlanId();

        logger.info(format("Creating service instance %s", serviceInstanceId));

        try {
            ServiceDefinitionProxy service = ecs
                    .lookupServiceDefinition(serviceDefinitionId);
            PlanProxy plan = service.findPlan(planId);
            InstanceWorkflow workflow = getWorkflow(request)
                    .withCreateRequest(request);

            LOG.info("creating service instance");
            ServiceInstance instance =
                    workflow.create(serviceInstanceId, service, plan, request.getParameters());

            LOG.info("saving instance...");
            repository.save(instance);

            return Mono.just(CreateServiceInstanceResponse.builder()
                    .async(false)
                    .build());
        } catch (Exception e) {
            logger.error(format("Unexpected error creating service %s", serviceInstanceId), e);
            throw new ServiceBrokerException(e);
        }
    }

    @Override
    public Mono<DeleteServiceInstanceResponse> deleteServiceInstance(DeleteServiceInstanceRequest request) {

        String serviceInstanceId = request.getServiceInstanceId();
        String serviceDefinitionId = request.getServiceDefinitionId();
        try {
            ServiceDefinitionProxy service = ecs
                .lookupServiceDefinition(serviceDefinitionId);
            InstanceWorkflow workflow = getWorkflow(service)
                .withDeleteRequest(request);

            ServiceInstance instance = null;
            try {
                instance = repository.find(serviceInstanceId);
                if (instance == null) {
                    LOG.info("Instance {} not found, assuming already deleted", serviceInstanceId);
                    return Mono.just(DeleteServiceInstanceResponse.builder()
                        .build());
                }
            } catch(S3Exception e) {
                LOG.info("Instance {} not found, assuming already deleted", serviceInstanceId);
                return Mono.just(DeleteServiceInstanceResponse.builder()
                    .build());
            }

            LOG.info("deleting service instance {}", serviceInstanceId);
            CompletableFuture future = workflow.delete(serviceInstanceId);
            if (future != null) {
                instance.setLastOperation(new LastOperationSerializer(OperationState.IN_PROGRESS, "Deleting", true));
                repository.save(instance);

                // Setup callback to handle asynchronous delete completion
                future.handle((result, exception) -> {
                        asyncDeleteCompleted(serviceInstanceId, exception == null);
                        return null;
                    });
            } else {
                LOG.info("removing instance {} from repo", serviceInstanceId);
                repository.delete(serviceInstanceId);
            }

            return Mono.just(DeleteServiceInstanceResponse.builder()
                .async(future != null)
                .build());
        } catch (Exception e) {
            logger.error("Error Deleting",e);
            throw new ServiceBrokerException(e);
        }
    }

    @Override
    public Mono<UpdateServiceInstanceResponse> updateServiceInstance(UpdateServiceInstanceRequest request) {
        String serviceInstanceId = request.getServiceInstanceId();
        String serviceDefinitionId = request.getServiceDefinitionId();
        String planId = request.getPlanId();
        try {
            ServiceDefinitionProxy service = ecs
                    .lookupServiceDefinition(serviceDefinitionId);
            ServiceInstance instance = repository.find(serviceInstanceId);
            if (instance == null)
                throw new ServiceInstanceDoesNotExistException(
                        serviceInstanceId);
            if (instance.getReferences().size() > 1)
                throw new ServiceInstanceUpdateNotSupportedException(
                        "Cannot change plan of service instance with remote references");

            InstanceWorkflow workflow = getWorkflow(service);
            LOG.info("changing instance plan");
            Map<String, Object> serviceSettings =
                    workflow.changePlan(serviceInstanceId, service, service.findPlan(planId), request.getParameters());

            LOG.info("updating service in repo");
            // This shouldn't be needed. The object will be re-versioned
            // repository.delete(serviceInstanceId);
            instance.update(request, serviceSettings);
            repository.save(instance);
            return Mono.just(UpdateServiceInstanceResponse.builder()
                    .async(false)
                    .build());
        } catch (ServiceInstanceDoesNotExistException e) {
            // Rethrow "does not exist" so that it's not caught by the generic casue
            throw e;
        } catch (Exception e) {
            throw new ServiceBrokerException(e);
        }
    }

    @Override
    public Mono<GetLastServiceOperationResponse> getLastOperation(GetLastServiceOperationRequest request) {
        try {
            ServiceInstance instance = repository.find(request.getServiceInstanceId());

            if (instance == null)
                throw new ServiceInstanceDoesNotExistException(request.getServiceInstanceId());

            // No stored operation, assume succeeded
            LastOperationSerializer lastOperation = instance.getLastOperation();
            if (lastOperation == null) {
                lastOperation = SUCCEEDED_OPERATION;
            }

            // Possibly delete the repository record
            if (lastOperation.isDeleteOperation() && lastOperation.getOperationState() == OperationState.SUCCEEDED) {
                logger.info("Operation for {} completed successfully, deleting from repository", instance.getServiceInstanceId(), lastOperation.getOperationState());
                repository.delete(instance.getServiceInstanceId());
            }

            return Mono.just(GetLastServiceOperationResponse.builder()
                .deleteOperation(lastOperation.isDeleteOperation())
                .description(lastOperation.getDescription())
                .operationState(lastOperation.getOperationState())
                .build());
        } catch (IOException e) {
            throw new ServiceBrokerException(e);
        }
    }

    private InstanceWorkflow getWorkflow(CreateServiceInstanceRequest createRequest)
            throws EcsManagementClientException {
        if (isRemoteConnection(createRequest))
            return new RemoteConnectionInstanceWorkflow(repository, ecs);
        ServiceDefinitionProxy service = ecs.lookupServiceDefinition(createRequest.getServiceDefinitionId());
        return getWorkflow(service);
    }

    private boolean isRemoteConnection(CreateServiceInstanceRequest createRequest) {
        Map<String, Object> parameters = createRequest.getParameters();
        return parameters != null && parameters.containsKey("remote_connection");
    }

    private InstanceWorkflow getWorkflow(ServiceDefinitionProxy service)
            throws EcsManagementClientException {
        String serviceType = (String) service.getServiceSettings().get(SERVICE_TYPE);
        switch (serviceType) {
            case NAMESPACE:
                return new NamespaceInstanceWorkflow(repository, ecs);
            case BUCKET:
                return new BucketInstanceWorkflow(repository, ecs);
            default:
                throw new ServiceBrokerException(NO_SERVICE_MATCHING_TYPE +
                        serviceType);
        }
    }

    private void asyncDeleteCompleted(String instanceId, boolean success) {
        try {
            ServiceInstance instance = repository.find(instanceId);
            if (instance == null) {
                logger.error("Unable to find instance {} when delete completed async", instanceId);
            }

            OperationState state = success ? OperationState.SUCCEEDED : OperationState.FAILED;

            logger.info("Async Delete of instance {} completed", instanceId);
            instance.setLastOperation(new LastOperationSerializer(state, "Deleting", true));
            repository.save(instance);
        } catch (IOException e) {
            logger.error("Unable to find instance {} when delete completed async");
        }
    }
}
