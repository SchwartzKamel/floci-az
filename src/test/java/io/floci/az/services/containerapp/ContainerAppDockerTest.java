package io.floci.az.services.containerapp;

import io.floci.az.services.containerapp.ContainerAppModels.ContainerApp;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@TestProfile(ContainerAppDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Container Apps — real Docker-backed mode")
class ContainerAppDockerTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.test-port", "0",
                    "floci-az.services.container-app.mocked", "false");
        }
    }

    private static final String SUB = "test-sub-ca-docker";
    private static final String RG = "test-rg-ca-docker";
    private static final String NAME = "dockerapp";
    private static final String API = "?api-version=2023-05-01";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.App";

    private static final String ENV_BODY = """
            {
              "location": "eastus",
              "properties": {"appLogsConfiguration": {"destination": "none"}}
            }
            """;

    private static final String APP_BODY = """
            {
              "location": "eastus",
              "properties": {
                "managedEnvironmentId": "/subscriptions/test-sub-ca-docker/resourceGroups/test-rg-ca-docker/providers/Microsoft.App/managedEnvironments/env1",
                "configuration": {
                  "ingress": {
                    "external": true,
                    "targetPort": 80,
                    "traffic": [{"latestRevision": true, "weight": 100}]
                  }
                },
                "template": {
                  "containers": [{
                    "name": "hello",
                    "image": "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"
                  }],
                  "scale": {"minReplicas": 1, "maxReplicas": 2}
                }
              }
            }
            """;

    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock")) || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available — skipping Container App Docker tests");
    }

    @AfterAll
    void cleanup() {
        try {
            given().delete(BASE + "/containerApps/" + NAME + API);
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    @DisplayName("PUT managed environment and container app returns 201/Creating")
    void createContainerApp() {
        given().post("/_admin/reset").then().statusCode(204);

        given().contentType("application/json").body(ENV_BODY)
                .when().put(BASE + "/managedEnvironments/env1" + API)
                .then().statusCode(201)
                .body("properties.provisioningState", equalTo("Succeeded"));

        given().contentType("application/json").body(APP_BODY)
                .when().put(BASE + "/containerApps/" + NAME + API)
                .then().statusCode(201)
                .body("properties.provisioningState", not(emptyOrNullString()));
    }

    @Test
    @Order(2)
    @DisplayName("container app reaches Succeeded and ingress is reachable")
    void appReachesSucceededAndResponds() throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        String state = "Creating";
        String fqdn = null;

        while (!"Succeeded".equals(state) && System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000);
            Response response = given().when().get(BASE + "/containerApps/" + NAME + API);
            if (response.statusCode() == 200) {
                state = response.path("properties.provisioningState");
                fqdn = response.path("properties.configuration.ingress.fqdn");
            }
        }

        assertEquals("Succeeded", state, "Container App did not reach Succeeded in time");
        assertTrue(fqdn != null && fqdn.startsWith("localhost:"), "Expected localhost ingress, got " + fqdn);

        int status = httpStatus("http://" + fqdn + "/");
        assertTrue(status >= 200 && status < 500, "Expected an HTTP response from ingress, got status " + status);

        ContainerApp app = new ContainerApp();
        app.setSubscriptionId(SUB);
        app.setResourceGroup(RG);
        app.setName(NAME);
        assertTrue(dockerCommandSucceeds("inspect", ContainerAppContainerManager.containerName(app)),
                "Expected backing container to exist");
    }

    @Test
    @Order(3)
    @DisplayName("DELETE removes the backing container")
    void deleteRemovesContainer() {
        given().when().delete(BASE + "/containerApps/" + NAME + API).then().statusCode(204);
        given().when().get(BASE + "/containerApps/" + NAME + API).then().statusCode(404);

        ContainerApp app = new ContainerApp();
        app.setSubscriptionId(SUB);
        app.setResourceGroup(RG);
        app.setName(NAME);
        assertTrue(!dockerCommandSucceeds("inspect", ContainerAppContainerManager.containerName(app)),
                "Expected backing container to be removed");
    }

    private static int httpStatus(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    private static boolean dockerCommandSucceeds(String... args) {
        try {
            Process process = new ProcessBuilder(concat("docker", args)).redirectErrorStream(true).start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String[] concat(String first, String[] rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }
}
