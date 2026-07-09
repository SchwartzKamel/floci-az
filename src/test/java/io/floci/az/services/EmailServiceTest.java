package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
public class EmailServiceTest {

    private static final String SUB = "00000000-0000-0000-0000-000000000111";
    private static final String RG = "email-rg";
    private static final String API = "?api-version=2023-04-01";
    private static final String COMMUNICATION_SERVICE = "mail-comm";
    private static final String EMAIL_SERVICE = "mail-service";
    private static final String DOMAIN = "example.com";
    private static final String EMAIL_HOST = "mailer.communication.azure.com";
    private static final String EMAIL_SUFFIX_ACCOUNT = "mailer-email";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void communicationServicesCrud() {
        String path = communicationServicePath();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "location", "eastus",
                "tags", Map.of("env", "test")))
            .when().put(path)
            .then().statusCode(201)
            .body("id", equalTo(pathWithoutQuery(path)))
            .body("name", equalTo(COMMUNICATION_SERVICE))
            .body("type", equalTo("Microsoft.Communication/communicationServices"))
            .body("location", equalTo("eastus"))
            .body("tags.env", equalTo("test"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.hostName", equalTo(COMMUNICATION_SERVICE + ".communication.azure.com"))
            .body("properties.immutableResourceId", notNullValue());

        given()
            .when().get(path)
            .then().statusCode(200)
            .body("name", equalTo(COMMUNICATION_SERVICE));

        given()
            .when().get(communicationServicesListPath())
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo(COMMUNICATION_SERVICE));

        given()
            .when().delete(path)
            .then().statusCode(200);

        given()
            .when().get(path)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", containsString("communicationServices/" + COMMUNICATION_SERVICE));
    }

    @Test
    void emailServicesCrud() {
        String path = emailServicePath();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "location", "westus",
                "tags", Map.of("owner", "compat")))
            .when().put(path)
            .then().statusCode(201)
            .body("id", equalTo(pathWithoutQuery(path)))
            .body("name", equalTo(EMAIL_SERVICE))
            .body("type", equalTo("Microsoft.Communication/emailServices"))
            .body("location", equalTo("westus"))
            .body("tags.owner", equalTo("compat"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.dataLocation", equalTo("United States"));

        given()
            .when().get(path)
            .then().statusCode(200)
            .body("name", equalTo(EMAIL_SERVICE));

        given()
            .when().get(emailServicesListPath())
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo(EMAIL_SERVICE));

        given()
            .when().delete(path)
            .then().statusCode(200);

        given()
            .when().get(path)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", containsString("emailServices/" + EMAIL_SERVICE));
    }

    @Test
    void emailDomainsCrud() {
        String path = emailDomainPath();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "location", "global",
                "properties", Map.of("domainManagement", "AzureManaged"),
                "tags", Map.of("tier", "compat")))
            .when().put(path)
            .then().statusCode(201)
            .body("id", equalTo(pathWithoutQuery(path)))
            .body("name", equalTo(DOMAIN))
            .body("type", equalTo("Microsoft.Communication/emailServices/domains"))
            .body("location", equalTo("global"))
            .body("tags.tier", equalTo("compat"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.domainManagement", equalTo("AzureManaged"))
            .body("properties.fromSenderDomain", equalTo(DOMAIN))
            .body("properties.mailFromSenderDomain", equalTo(DOMAIN));

        given()
            .when().get(path)
            .then().statusCode(200)
            .body("name", equalTo(DOMAIN));

        given()
            .when().get(emailDomainsListPath())
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo(DOMAIN));

        given()
            .when().delete(path)
            .then().statusCode(200);

        given()
            .when().get(path)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", containsString("domains/" + DOMAIN));
    }

    @Test
    void sendStatusAndCapturedMessagesWorkAcrossSupportedRoutes() {
        Response sendResponse = given()
            .header("Host", EMAIL_HOST)
            .contentType(ContentType.JSON)
            .body(emailSendPayload())
            .when().post("/emails:send?api-version=2023-03-31")
            .then().statusCode(202)
            .header("Operation-Location", matchesRegex("http://localhost:4577/emails/operations/[0-9a-f\\-]+\\?api-version=2023-03-31"))
            .header("x-ms-request-id", notNullValue())
            .body("id", matchesRegex("[0-9a-f\\-]{36}"))
            .body("status", equalTo("Running"))
            .extract().response();

        String operationId = sendResponse.path("id");

        given()
            .when().get("/emails/operations/{operationId}?api-version=2023-03-31", operationId)
            .then().statusCode(200)
            .body("id", equalTo(operationId))
            .body("status", equalTo("Succeeded"))
            .body("resourceLocation", matchesRegex("[0-9a-f\\-]{36}"))
            .body("error", notNullValue());

        given()
            .when().get("/" + EMAIL_SUFFIX_ACCOUNT + "/emailMessages")
            .then().statusCode(200)
            .body("count", equalTo(1))
            .body("value", hasSize(1))
            .body("value[0].operationId", equalTo(operationId))
            .body("value[0].status", equalTo("Succeeded"))
            .body("value[0].senderAddress", equalTo("DoNotReply@contoso.com"))
            .body("value[0].subject", equalTo("Welcome to Floci-Az"))
            .body("value[0].toCount", equalTo(2))
            .body("value[0].messageId", matchesRegex("[0-9a-f\\-]{36}"))
            .body("value[0].sentAt", notNullValue());

        given()
            .when().get("/" + EMAIL_SUFFIX_ACCOUNT + "/emailMessages/{operationId}", operationId)
            .then().statusCode(200)
            .body(containsString("\"X-Correlation-Id\":\"corr-123\""))
            .body("operationId", equalTo(operationId))
            .body("status", equalTo("Succeeded"))
            .body("messageId", matchesRegex("[0-9a-f\\-]{36}"))
            .body("request.senderAddress", equalTo("DoNotReply@contoso.com"))
            .body("request.content.subject", equalTo("Welcome to Floci-Az"))
            .body("request.content.plainText", equalTo("Hello from the emulator"))
            .body("request.content.html", equalTo("<p>Hello from the emulator</p>"))
            .body("request.recipients.to.address", equalTo(List.of("alice@example.com", "bob@example.com")))
            .body("request.replyTo[0].address", equalTo("support@example.com"))
            .body("request.attachments[0].name", equalTo("hello.txt"))
            .body("request.attachments[0].contentType", equalTo("text/plain"));

        given()
            .when().delete("/" + EMAIL_SUFFIX_ACCOUNT + "/emailMessages")
            .then().statusCode(200)
            .body("message", equalTo("All captured emails cleared"));

        given()
            .when().get("/emailMessages")
            .then().statusCode(200)
            .body("count", equalTo(0))
            .body("value", hasSize(0));
    }

    @Test
    void missingOperationReturnsAzureErrorEnvelope() {
        given()
            .when().get("/emails/operations/{operationId}?api-version=2023-03-31", "missing-operation")
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", containsString("missing-operation"));
    }

    @Test
    void malformedSendPayloadReturnsAzureErrorEnvelope() {
        given()
            .header("Host", EMAIL_HOST)
            .contentType(ContentType.JSON)
            .body("{")
            .when().post("/emails:send?api-version=2023-03-31")
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidRequest"))
            .body("error.message", containsString("Invalid email send request"));
    }

    private static Map<String, Object> emailSendPayload() {
        return Map.of(
            "senderAddress", "DoNotReply@contoso.com",
            "content", Map.of(
                "subject", "Welcome to Floci-Az",
                "plainText", "Hello from the emulator",
                "html", "<p>Hello from the emulator</p>"),
            "recipients", Map.of(
                "to", List.of(
                    Map.of("address", "alice@example.com", "displayName", "Alice"),
                    Map.of("address", "bob@example.com", "displayName", "Bob")),
                "cc", List.of(Map.of("address", "manager@example.com", "displayName", "Manager")),
                "bcc", List.of(Map.of("address", "audit@example.com", "displayName", "Audit"))),
            "replyTo", List.of(Map.of("address", "support@example.com", "displayName", "Support")),
            "attachments", List.of(Map.of(
                "name", "hello.txt",
                "contentType", "text/plain",
                "contentInBase64", "aGVsbG8=")),
            "headers", Map.of("X-Correlation-Id", "corr-123"),
            "userEngagementTrackingDisabled", true);
    }

    private static String communicationServicePath() {
        return String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Communication/communicationServices/%s%s",
            SUB, RG, COMMUNICATION_SERVICE, API);
    }

    private static String communicationServicesListPath() {
        return String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Communication/communicationServices%s",
            SUB, RG, API);
    }

    private static String emailServicePath() {
        return String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Communication/emailServices/%s%s",
            SUB, RG, EMAIL_SERVICE, API);
    }

    private static String emailServicesListPath() {
        return String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Communication/emailServices%s",
            SUB, RG, API);
    }

    private static String emailDomainPath() {
        return String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Communication/emailServices/%s/domains/%s%s",
            SUB, RG, EMAIL_SERVICE, DOMAIN, API);
    }

    private static String emailDomainsListPath() {
        return String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Communication/emailServices/%s/domains%s",
            SUB, RG, EMAIL_SERVICE, API);
    }

    private static String pathWithoutQuery(String path) {
        int queryIndex = path.indexOf('?');
        return queryIndex >= 0 ? path.substring(0, queryIndex) : path;
    }
}
