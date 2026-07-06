package io.floci.az.services.eventhub;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Fast management-plane regression tests for {@link EventHubHandler}.
 *
 * <p>These tests intentionally rely on the default mocked Event Hubs configuration so they can
 * verify namespace-management behavior without starting Artemis containers.
 */
@QuarkusTest
@DisplayName("EventHubHandler — mocked namespace management")
class EventHubHandlerTest {

    private static final String BASE = "/devstoreaccount1-eventhub/namespaces";
    private static final String DEFAULT_NAMESPACE = "emulatorNs1";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    @DisplayName("mocked namespace tls-cert returns actionable 503 JSON")
    void mockedNamespaceTlsCertReturnsActionable503() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().put(BASE + "/mocked-ns")
            .then().statusCode(201)
            .body("name", equalTo("mocked-ns"))
            .body("mocked", equalTo(true));

        given()
            .when().get(BASE + "/mocked-ns/tls-cert")
            .then().statusCode(503)
            .body("error", equalTo("TLS certificate not available for namespace: mocked-ns"))
            .body("message", containsString("mocked management-only mode"));
    }

    @Test
    @DisplayName("backward-compatible default tls-cert returns actionable 503 in mocked mode")
    void defaultTlsCertReturnsActionable503InMockedMode() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().put(BASE + "/" + DEFAULT_NAMESPACE)
            .then().statusCode(201)
            .body("name", equalTo(DEFAULT_NAMESPACE))
            .body("mocked", equalTo(true));

        given()
            .when().get("/devstoreaccount1-eventhub/tls-cert")
            .then().statusCode(503)
            .body("error", equalTo("TLS certificate not available for namespace: " + DEFAULT_NAMESPACE))
            .body("message", containsString("mocked management-only mode"));
    }

    @Test
    @DisplayName("missing namespace tls-cert stays 404")
    void missingNamespaceTlsCertStays404() {
        given()
            .when().get(BASE + "/missing/tls-cert")
            .then().statusCode(404)
            .body("error", equalTo("Namespace not found: missing"));
    }
}
