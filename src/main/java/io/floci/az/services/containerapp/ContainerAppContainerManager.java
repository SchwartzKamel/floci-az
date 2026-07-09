package io.floci.az.services.containerapp;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.containerapp.ContainerAppModels.ContainerApp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class ContainerAppContainerManager {

    private static final Logger LOG = Logger.getLogger(ContainerAppContainerManager.class);
    private static final int CONNECT_TIMEOUT_MS = 2000;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    @Inject
    public ContainerAppContainerManager(ContainerBuilder containerBuilder,
                                        ContainerLifecycleManager lifecycleManager,
                                        ContainerDetector containerDetector,
                                        PortAllocator portAllocator,
                                        EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    public void startApp(ContainerApp app) {
        int targetPort = resolveTargetPort(app);
        int hostPort = portAllocator.allocate(config.services().containerApp().basePort(),
                config.services().containerApp().maxPort());
        String requestedImage = resolveImage(app);
        String defaultImage = config.services().containerApp().defaultImage();

        lifecycleManager.removeIfExists(containerName(app));
        try {
            createAndStart(app, requestedImage, targetPort, hostPort);
        } catch (Exception firstFailure) {
            if (!defaultImage.equals(requestedImage)) {
                LOG.warnv("Container app {0} image {1} failed, retrying with default image {2}",
                        app.getName(), requestedImage, defaultImage);
                lifecycleManager.removeIfExists(containerName(app));
                createAndStart(app, defaultImage, targetPort, hostPort);
            } else {
                portAllocator.release(hostPort);
                throw firstFailure;
            }
        }
    }

    public boolean isReady(ContainerApp app) {
        if (app.getContainerId() == null || app.getInternalEndpoint() == null) {
            return false;
        }
        if (!lifecycleManager.isContainerRunning(app.getContainerId())) {
            return false;
        }
        int sep = app.getInternalEndpoint().lastIndexOf(':');
        if (sep <= 0 || sep == app.getInternalEndpoint().length() - 1) {
            return false;
        }
        String host = app.getInternalEndpoint().substring(0, sep);
        int port;
        try {
            port = Integer.parseInt(app.getInternalEndpoint().substring(sep + 1));
        } catch (NumberFormatException e) {
            return false;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void removeApp(ContainerApp app) {
        if (app.getContainerId() != null) {
            lifecycleManager.stopAndRemove(app.getContainerId(), null);
        } else {
            lifecycleManager.removeIfExists(containerName(app));
        }
        if (app.getHostPort() != null) {
            portAllocator.release(app.getHostPort());
        }
        app.setContainerId(null);
    }

    static String containerName(ContainerApp app) {
        return "floci-az-ca-" + sanitize(app.getSubscriptionId())
                + "-" + sanitize(app.getResourceGroup())
                + "-" + sanitize(app.getName());
    }

    private void createAndStart(ContainerApp app, String image, int targetPort, int hostPort) {
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName(app))
                .withPortBinding(targetPort, hostPort)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();

        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
        app.setContainerId(info.containerId());
        app.setTargetPort(targetPort);
        app.setHostPort(hostPort);

        if (containerDetector.isRunningInContainer()) {
            app.setIngressHost(containerName(app));
            app.setIngressPort(targetPort);
            app.setInternalEndpoint(containerName(app) + ":" + targetPort);
        } else {
            ContainerLifecycleManager.EndpointInfo endpoint = info.getEndpoint(targetPort);
            app.setIngressHost("localhost");
            app.setIngressPort(hostPort);
            app.setInternalEndpoint(endpoint != null
                    ? endpoint.host() + ":" + endpoint.port()
                    : "localhost:" + hostPort);
        }

        LOG.infov("Container App {0} started on {1}:{2}", app.getName(), app.getIngressHost(), app.getIngressPort());
    }

    private int resolveTargetPort(ContainerApp app) {
        Integer targetPort = app.getTargetPort();
        return targetPort != null && targetPort > 0 ? targetPort : 80;
    }

    @SuppressWarnings("unchecked")
    private String resolveImage(ContainerApp app) {
        if (app.getProperties() == null) {
            return config.services().containerApp().defaultImage();
        }
        Object templateObj = app.getProperties().get("template");
        if (templateObj instanceof Map<?, ?> template) {
            Object containersObj = ((Map<String, Object>) template).get("containers");
            if (containersObj instanceof List<?> containers && !containers.isEmpty()) {
                Object first = containers.get(0);
                if (first instanceof Map<?, ?> container) {
                    Object image = ((Map<String, Object>) container).get("image");
                    if (image != null && !image.toString().isBlank()) {
                        return image.toString();
                    }
                }
            }
        }
        return config.services().containerApp().defaultImage();
    }

    private static String sanitize(String value) {
        String sanitized = value == null ? "default" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "default" : sanitized;
    }
}
