package io.floci.az.services.containerapp;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;

@QuarkusTest
@TestProfile(ContainerAppHandlerTest.MockedProfile.class)
@DisplayName("Container Apps — ARM CRUD in mocked mode")
@SuppressWarnings("unused")
public class ContainerAppHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.test-port", "0",
                    "floci-az.services.container-app.mocked", "true",
                    "floci-az.services.container-app.enabled", "true");
        }
    }

    private static final String SUB = "test-sub-ca";
    private static final String RG = "test-rg-ca";
    private static final String API = "?api-version=2023-05-01";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.App";

    private static final String ENV_BODY = """
            {
              "location": "eastus",
              "tags": {"env": "test"},
              "properties": {
                "appLogsConfiguration": {"destination": "log-analytics"}
              }
            }
            """;

    private static final String APP_BODY = """
            {
              "location": "eastus",
              "tags": {"tier": "web"},
              "properties": {
                "managedEnvironmentId": "/subscriptions/test-sub-ca/resourceGroups/test-rg-ca/providers/Microsoft.App/managedEnvironments/env1",
                "configuration": {
                  "ingress": {
                    "external": true,
                    "targetPort": 80,
                    "traffic": [{"latestRevision": true, "weight": 100}]
                  }
                },
                "template": {
                  "containers": [{
                    "name": "web",
                    "image": "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"
                  }],
                  "scale": {"minReplicas": 1, "maxReplicas": 2}
                }
              }
            }
            """;

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    @DisplayName("managed environment CRUD and list endpoints work")
    void managedEnvironmentCrud() {
        given().contentType("application/json").body(ENV_BODY)
                .when().put(BASE + "/managedEnvironments/env1" + API)
                .then().statusCode(201)
                .body("name", equalTo("env1"))
                .body("type", equalTo("Microsoft.App/managedEnvironments"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("tags.env", equalTo("test"));

        given().when().get(BASE + "/managedEnvironments/env1" + API)
                .then().statusCode(200)
                .body("properties.appLogsConfiguration.destination", equalTo("log-analytics"));

        given().when().get(BASE + "/managedEnvironments" + API)
                .then().statusCode(200)
                .body("value.name", hasItems("env1"));

        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.App/managedEnvironments" + API)
                .then().statusCode(200)
                .body("value.name", hasItems("env1"));

        given().when().delete(BASE + "/managedEnvironments/env1" + API).then().statusCode(204);
        given().when().get(BASE + "/managedEnvironments/env1" + API).then().statusCode(404);
    }

    @Test
    @DisplayName("container app CRUD, ingress echo, and list endpoints work through Microsoft.App routing")
    void containerAppCrud() {
        given().contentType("application/json").body(ENV_BODY)
                .when().put(BASE + "/managedEnvironments/env1" + API)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));

        given().contentType("application/json").body(APP_BODY)
                .when().put(BASE + "/containerApps/app1" + API)
                .then().statusCode(201)
                .body("name", equalTo("app1"))
                .body("type", equalTo("Microsoft.App/containerApps"))
                .body("tags.tier", equalTo("web"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.runningStatus", equalTo("Running"))
                .body("properties.template.scale.minReplicas", equalTo(1))
                .body("properties.template.scale.maxReplicas", equalTo(2))
                .body("properties.configuration.ingress.fqdn", equalTo("localhost:80"));

        given().when().get(BASE + "/containerApps/app1" + API)
                .then().statusCode(200)
                .body("properties.managedEnvironmentId", not(emptyOrNullString()));

        given().when().get(BASE + "/containerApps" + API)
                .then().statusCode(200)
                .body("value.name", hasItems("app1"));

        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.App/containerApps" + API)
                .then().statusCode(200)
                .body("value.name", hasItems("app1"));

        given().when().delete(BASE + "/containerApps/app1" + API).then().statusCode(204);
        given().when().get(BASE + "/containerApps/app1" + API).then().statusCode(404)
                .header("x-ms-error-code", equalTo("ResourceNotFound"));
    }
}

@QuarkusTest
@TestProfile(ContainerAppDisabledTest.DisabledProfile.class)
@DisplayName("Container Apps disabled — Microsoft.App gated off")
@SuppressWarnings("unused")
class ContainerAppDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.test-port", "0",
                    "floci-az.services.container-app.enabled", "false");
        }
    }

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    @DisplayName("PUT container app returns 404 when service disabled")
    void containerAppCreateIsGatedOff() {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"properties\":{}}")
                .when().put("/subscriptions/off-sub/resourceGroups/off-rg/providers/Microsoft.App/containerApps/app1?api-version=2023-05-01")
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }
}
