package io.floci.az.services.containerapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.containerapp.ContainerAppModels.ContainerApp;
import io.floci.az.services.containerapp.ContainerAppModels.ManagedEnvironment;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ContainerAppHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(ContainerAppHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String APP_MARKER = "/providers/Microsoft.App/";

    private final EmulatorConfig config;
    private final ContainerAppContainerManager containerManager;
    private final StorageBackend<String, StoredObject> appStorage;
    private final StorageBackend<String, StoredObject> environmentStorage;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "containerapp-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public ContainerAppHandler(EmulatorConfig config,
                               ContainerAppContainerManager containerManager,
                               StorageFactory storageFactory) {
        this.config = config;
        this.containerManager = containerManager;
        this.appStorage = storageFactory.create("containerapp");
        this.environmentStorage = storageFactory.create("containerapp-env");
    }

    @PostConstruct
    public void init() {
        if (!config.services().containerApp().mocked()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().containerApp().mocked()) {
            scanApps().forEach(app -> {
                try {
                    containerManager.removeApp(app);
                } catch (Exception e) {
                    LOG.warnv("Error removing container for Container App {0}: {1}", app.getName(), e.getMessage());
                }
            });
        }
    }

    @Override
    public String getServiceType() {
        return "containerapp";
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "containerapp".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String fullPath = request.resourcePath();
        String method = request.method().toUpperCase();
        String tail = extractAppPath(fullPath);

        if (tail.matches("managedEnvironments(?:[?].*)?") && !fullPath.contains("/resourceGroups/")) {
            return "GET".equals(method)
                    ? listManagedEnvironmentsBySubscription(extractSubscriptionId(fullPath))
                    : methodNotAllowed();
        }
        if (tail.matches("managedEnvironments(?:[?].*)?") && "GET".equals(method)) {
            return listManagedEnvironmentsByResourceGroup(extractSubscriptionId(fullPath), extractResourceGroup(fullPath));
        }
        if (tail.matches("managedEnvironments/[^/]+(?:[?].*)?")) {
            String name = segment(tail, 1);
            String sub = extractSubscriptionId(fullPath);
            String rg = extractResourceGroup(fullPath);
            return switch (method) {
                case "GET" -> getManagedEnvironment(sub, rg, name);
                case "PUT" -> putManagedEnvironment(sub, rg, name, request.bodyStream());
                case "DELETE" -> deleteManagedEnvironment(sub, rg, name);
                default -> methodNotAllowed();
            };
        }

        if (tail.matches("containerApps(?:[?].*)?") && !fullPath.contains("/resourceGroups/")) {
            return "GET".equals(method)
                    ? listContainerAppsBySubscription(extractSubscriptionId(fullPath))
                    : methodNotAllowed();
        }
        if (tail.matches("containerApps(?:[?].*)?") && "GET".equals(method)) {
            return listContainerAppsByResourceGroup(extractSubscriptionId(fullPath), extractResourceGroup(fullPath));
        }
        if (tail.matches("containerApps/[^/]+(?:[?].*)?")) {
            String name = segment(tail, 1);
            String sub = extractSubscriptionId(fullPath);
            String rg = extractResourceGroup(fullPath);
            return switch (method) {
                case "GET" -> getContainerApp(sub, rg, name);
                case "PUT" -> putContainerApp(sub, rg, name, request.bodyStream());
                case "DELETE" -> deleteContainerApp(sub, rg, name);
                default -> methodNotAllowed();
            };
        }

        return notFound("Unsupported Microsoft.App path: " + tail);
    }

    private Response putManagedEnvironment(String sub, String rg, String name, InputStream bodyStream) {
        try {
            JsonNode body = readBody(bodyStream);
            ManagedEnvironment env = getManagedEnvironmentModel(storageKey(sub, rg, name))
                    .orElseGet(ManagedEnvironment::new);
            boolean isNew = env.getCreatedAt() == null;
            if (isNew) {
                env.setSubscriptionId(sub);
                env.setResourceGroup(rg);
                env.setName(name);
                env.setCreatedAt(Instant.now());
            }
            env.setLocation(body.path("location").asText(env.getLocation() == null ? "eastus" : env.getLocation()));
            env.setTags(parseTags(body.path("tags")));
            env.setProperties(objectToMap(body.path("properties")));
            env.setProvisioningState("Succeeded");
            putEnvironment(env.storageKey(), env);
            return Response.status(isNew ? 201 : 200)
                    .entity(toArmResponse(env))
                    .type("application/json")
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating managed environment %s", name);
            return invalidRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response getManagedEnvironment(String sub, String rg, String name) {
        return getManagedEnvironmentModel(storageKey(sub, rg, name))
                .map(env -> Response.ok(toArmResponse(env)).type("application/json").build())
                .orElseGet(() -> notFound("Managed environment '" + name + "' was not found."));
    }

    private Response deleteManagedEnvironment(String sub, String rg, String name) {
        environmentStorage.delete(storageKey(sub, rg, name));
        return Response.status(204).build();
    }

    private Response listManagedEnvironmentsBySubscription(String sub) {
        List<Map<String, Object>> items = new ArrayList<>();
        scanEnvironments().stream()
                .filter(env -> env.storageKey().startsWith(sub + "/"))
                .forEach(env -> items.add(toArmResponse(env)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response listManagedEnvironmentsByResourceGroup(String sub, String rg) {
        String prefix = (sub + "/" + rg + "/").toLowerCase();
        List<Map<String, Object>> items = new ArrayList<>();
        scanEnvironments().stream()
                .filter(env -> env.storageKey().toLowerCase().startsWith(prefix))
                .forEach(env -> items.add(toArmResponse(env)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response putContainerApp(String sub, String rg, String name, InputStream bodyStream) {
        try {
            JsonNode body = readBody(bodyStream);
            String key = storageKey(sub, rg, name);
            Optional<ContainerApp> existing = getContainerAppModel(key);
            boolean isNew = existing.isEmpty();

            ContainerApp app = existing.orElseGet(ContainerApp::new);
            if (isNew) {
                app.setSubscriptionId(sub);
                app.setResourceGroup(rg);
                app.setName(name);
                app.setCreatedAt(Instant.now());
            }
            app.setLocation(body.path("location").asText(app.getLocation() == null ? "eastus" : app.getLocation()));
            app.setTags(parseTags(body.path("tags")));
            app.setProperties(objectToMap(body.path("properties")));
            app.setTargetPort(resolveTargetPort(body.path("properties").path("configuration").path("ingress"), app.getTargetPort()));

            if (config.services().containerApp().mocked()) {
                setMockedIngress(app);
                app.setProvisioningState("Succeeded");
                app.setRunningStatus("Running");
            } else {
                if (existing.isPresent()) {
                    try {
                        containerManager.removeApp(existing.get());
                    } catch (Exception e) {
                        LOG.warnv("Could not remove old container for Container App {0}: {1}", name, e.getMessage());
                    }
                }
                app.setProvisioningState("Creating");
                app.setRunningStatus("Provisioning");
                try {
                    containerManager.startApp(app);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to start container for Container App %s; degrading to mocked state", name);
                    setMockedIngress(app);
                    app.setProvisioningState("Succeeded");
                    app.setRunningStatus("Running");
                }
            }

            putApp(key, app);
            return Response.status(isNew ? 201 : 200)
                    .entity(toArmResponse(app))
                    .type("application/json")
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating Container App %s", name);
            return invalidRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response getContainerApp(String sub, String rg, String name) {
        return getContainerAppModel(storageKey(sub, rg, name))
                .map(app -> Response.ok(toArmResponse(app)).type("application/json").build())
                .orElseGet(() -> notFound("Container App '" + name + "' was not found."));
    }

    private Response deleteContainerApp(String sub, String rg, String name) {
        String key = storageKey(sub, rg, name);
        getContainerAppModel(key).ifPresent(app -> {
            if (!config.services().containerApp().mocked()) {
                try {
                    containerManager.removeApp(app);
                } catch (Exception e) {
                    LOG.warnv("Error removing container for Container App {0}: {1}", name, e.getMessage());
                }
            }
        });
        appStorage.delete(key);
        return Response.status(204).build();
    }

    private Response listContainerAppsBySubscription(String sub) {
        List<Map<String, Object>> items = new ArrayList<>();
        scanApps().stream()
                .filter(app -> app.storageKey().startsWith(sub + "/"))
                .forEach(app -> items.add(toArmResponse(app)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response listContainerAppsByResourceGroup(String sub, String rg) {
        String prefix = (sub + "/" + rg + "/").toLowerCase();
        List<Map<String, Object>> items = new ArrayList<>();
        scanApps().stream()
                .filter(app -> app.storageKey().toLowerCase().startsWith(prefix))
                .forEach(app -> items.add(toArmResponse(app)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Map<String, Object> toArmResponse(ManagedEnvironment env) {
        Map<String, Object> props = mutableMap(env.getProperties());
        props.put("provisioningState", env.getProvisioningState());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", env.armId());
        out.put("name", env.getName());
        out.put("type", "Microsoft.App/managedEnvironments");
        out.put("location", env.getLocation());
        if (env.getTags() != null && !env.getTags().isEmpty()) {
            out.put("tags", env.getTags());
        }
        out.put("properties", props);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toArmResponse(ContainerApp app) {
        Map<String, Object> props = mutableMap(app.getProperties());
        Map<String, Object> configuration = mutableMap(props.get("configuration"));
        Map<String, Object> ingress = mutableMap(configuration.get("ingress"));
        if (app.getTargetPort() != null) {
            ingress.put("targetPort", app.getTargetPort());
        }
        String fqdn = ingressAddress(app);
        if (fqdn != null) {
            ingress.put("fqdn", fqdn);
            props.put("latestRevisionFqdn", fqdn);
        }
        if (!ingress.isEmpty()) {
            configuration.put("ingress", ingress);
        }
        if (!configuration.isEmpty()) {
            props.put("configuration", configuration);
        }
        props.put("provisioningState", app.getProvisioningState());
        props.put("runningStatus", app.getRunningStatus());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", app.armId());
        out.put("name", app.getName());
        out.put("type", "Microsoft.App/containerApps");
        out.put("location", app.getLocation());
        if (app.getTags() != null && !app.getTags().isEmpty()) {
            out.put("tags", app.getTags());
        }
        out.put("properties", props);
        return out;
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                scanApps().forEach(app -> {
                    if ("Creating".equals(app.getProvisioningState()) && containerManager.isReady(app)) {
                        app.setProvisioningState("Succeeded");
                        app.setRunningStatus("Running");
                        putApp(app.storageKey(), app);
                        LOG.infov("Container App {0} is reachable; provisioningState=Succeeded", app.getName());
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in Container App readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    private Optional<ManagedEnvironment> getManagedEnvironmentModel(String key) {
        return environmentStorage.get(key).map(so -> deserialize(so.data(), ManagedEnvironment.class));
    }

    private Optional<ContainerApp> getContainerAppModel(String key) {
        return appStorage.get(key).map(so -> deserialize(so.data(), ContainerApp.class));
    }

    private <T> T deserialize(byte[] data, Class<T> type) {
        try {
            return MAPPER.readValue(data, type);
        } catch (Exception e) {
            LOG.warnv("Failed to deserialize {0}: {1}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private void putEnvironment(String key, ManagedEnvironment env) {
        store(environmentStorage, key, env);
    }

    private void putApp(String key, ContainerApp app) {
        store(appStorage, key, app);
    }

    private void store(StorageBackend<String, StoredObject> storage, String key, Object model) {
        try {
            storage.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(model), Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize state: " + key, e);
        }
    }

    private List<ManagedEnvironment> scanEnvironments() {
        List<ManagedEnvironment> result = new ArrayList<>();
        environmentStorage.scan(k -> true).forEach(so -> {
            ManagedEnvironment env = deserialize(so.data(), ManagedEnvironment.class);
            if (env != null) {
                result.add(env);
            }
        });
        return result;
    }

    private List<ContainerApp> scanApps() {
        List<ContainerApp> result = new ArrayList<>();
        appStorage.scan(k -> true).forEach(so -> {
            ContainerApp app = deserialize(so.data(), ContainerApp.class);
            if (app != null) {
                result.add(app);
            }
        });
        return result;
    }

    private static String extractAppPath(String fullPath) {
        if (fullPath == null) {
            return "";
        }
        int idx = fullPath.indexOf(APP_MARKER);
        return idx >= 0 ? fullPath.substring(idx + APP_MARKER.length()) : fullPath;
    }

    private static String extractSubscriptionId(String fullPath) {
        return segmentAfter(fullPath, "subscriptions");
    }

    private static String extractResourceGroup(String fullPath) {
        return segmentAfter(fullPath, "resourcegroups");
    }

    private static String segmentAfter(String fullPath, String marker) {
        if (fullPath == null) {
            return "default";
        }
        String[] parts = fullPath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (marker.equalsIgnoreCase(parts[i])) {
                return parts[i + 1];
            }
        }
        return "default";
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("[/?]");
        return index < parts.length ? parts[index] : "";
    }

    private static String storageKey(String sub, String rg, String name) {
        return sub + "/" + rg + "/" + name;
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, Map.class);
    }

    private JsonNode readBody(InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    private static Integer resolveTargetPort(JsonNode ingress, Integer existingPort) {
        if (ingress != null && ingress.has("targetPort")) {
            return ingress.path("targetPort").asInt(existingPort != null ? existingPort : 80);
        }
        return existingPort != null ? existingPort : 80;
    }

    private void setMockedIngress(ContainerApp app) {
        int targetPort = app.getTargetPort() != null ? app.getTargetPort() : 80;
        app.setContainerId(null);
        app.setHostPort(targetPort);
        app.setIngressHost("localhost");
        app.setIngressPort(targetPort);
        app.setInternalEndpoint("localhost:" + targetPort);
    }

    private static String ingressAddress(ContainerApp app) {
        if (app.getIngressHost() == null) {
            return null;
        }
        if (app.getIngressPort() == null) {
            return app.getIngressHost();
        }
        return app.getIngressHost() + ":" + app.getIngressPort();
    }

    private static Response notFound(String message) {
        return new AzureErrorResponse("ResourceNotFound", message).toJsonResponse(404);
    }

    private static Response invalidRequest(String message) {
        return new AzureErrorResponse("InvalidRequest", message).toJsonResponse(400);
    }

    private static Response methodNotAllowed() {
        return new AzureErrorResponse("MethodNotAllowed", "Method not allowed").toJsonResponse(405);
    }

    public void clearAll() {
        if (!config.services().containerApp().mocked()) {
            scanApps().forEach(app -> {
                try {
                    containerManager.removeApp(app);
                } catch (Exception e) {
                    LOG.warnv("Error removing container for Container App {0}: {1}", app.getName(), e.getMessage());
                }
            });
        }
        appStorage.clear();
        environmentStorage.clear();
    }
}
