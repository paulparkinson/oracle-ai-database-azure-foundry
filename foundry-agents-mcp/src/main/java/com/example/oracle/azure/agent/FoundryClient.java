package com.example.oracle.azure.agent;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.function.Supplier;

/** Small Java adapter over the documented Foundry Agent Service REST API, not Microsoft Agent Framework. */
final class FoundryClient {
    private final URI endpoint;
    private final Supplier<String> bearer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static final ObjectMapper JSON = new ObjectMapper();

    FoundryClient(URI endpoint, Supplier<String> bearer) { this.endpoint = endpoint; this.bearer = bearer; }
    JsonNode request(String method, String suffix, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(endpoint.toString().replaceAll("/$", "") + suffix))
                .timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + bearer.get())
                .header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Foundry HTTP " + response.statusCode() + "; request ID "
                    + response.headers().firstValue("apim-request-id").orElse("unavailable"));
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }
}
