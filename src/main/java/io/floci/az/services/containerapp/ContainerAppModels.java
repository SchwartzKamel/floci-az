package io.floci.az.services.containerapp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

public final class ContainerAppModels {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ManagedEnvironment {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String provisioningState;
        private Instant createdAt;
        private Map<String, String> tags;
        private Map<String, Object> properties;

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }

        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.App/managedEnvironments/" + name;
        }

        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContainerApp {
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String provisioningState;
        private String runningStatus;
        private Instant createdAt;
        private Map<String, String> tags;
        private Map<String, Object> properties;
        private String containerId;
        private Integer targetPort;
        private Integer hostPort;
        private String ingressHost;
        private Integer ingressPort;
        private String internalEndpoint;

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }
        public String getRunningStatus() { return runningStatus; }
        public void setRunningStatus(String runningStatus) { this.runningStatus = runningStatus; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
        public String getContainerId() { return containerId; }
        public void setContainerId(String containerId) { this.containerId = containerId; }
        public Integer getTargetPort() { return targetPort; }
        public void setTargetPort(Integer targetPort) { this.targetPort = targetPort; }
        public Integer getHostPort() { return hostPort; }
        public void setHostPort(Integer hostPort) { this.hostPort = hostPort; }
        public String getIngressHost() { return ingressHost; }
        public void setIngressHost(String ingressHost) { this.ingressHost = ingressHost; }
        public Integer getIngressPort() { return ingressPort; }
        public void setIngressPort(Integer ingressPort) { this.ingressPort = ingressPort; }
        public String getInternalEndpoint() { return internalEndpoint; }
        public void setInternalEndpoint(String internalEndpoint) { this.internalEndpoint = internalEndpoint; }

        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.App/containerApps/" + name;
        }

        public String storageKey() {
            return subscriptionId + "/" + resourceGroup + "/" + name;
        }
    }

    private ContainerAppModels() {}
}
