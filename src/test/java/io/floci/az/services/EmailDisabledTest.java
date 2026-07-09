package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(EmailDisabledTest.DisabledProfile.class)
@DisplayName("Email disabled — ACS Email data and ARM planes gated off")
@SuppressWarnings("unused")
class EmailDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.email.enabled", "false");
        }
    }

    @Test
    void dataPlaneRoutesAreDisabled() {
        given()
            .when().get("/emailMessages")
            .then().statusCode(anyOf(equalTo(404), equalTo(501), equalTo(503)));
    }

    @Test
    void armRoutesAreDisabled() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/test-sub/resourceGroups/test-rg/providers/Microsoft.Communication/emailServices/email-off?api-version=2023-04-01")
            .then().statusCode(anyOf(equalTo(404), equalTo(503)));
    }
}
