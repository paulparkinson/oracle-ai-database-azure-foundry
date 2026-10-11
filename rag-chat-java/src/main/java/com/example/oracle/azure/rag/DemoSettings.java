package com.example.oracle.azure.rag;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("demo")
public record DemoSettings(String apiToken, String foundryEndpoint, String apiKey,
                           String chatDeployment, String embeddingDeployment, int dimensions, boolean initializeSchema) {
    public DemoSettings {
        if (apiToken == null || apiToken.length() < 32) throw new IllegalArgumentException("DEMO_API_TOKEN must contain at least 32 characters");
        URI endpoint = URI.create(foundryEndpoint);
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")))
            throw new IllegalArgumentException("FOUNDRY_ENDPOINT must be an HTTPS resource origin, without path/query/credentials");
        if (chatDeployment == null || chatDeployment.isBlank() || embeddingDeployment == null || embeddingDeployment.isBlank())
            throw new IllegalArgumentException("Both Foundry deployment names are required");
        if (dimensions < 1 || dimensions > 65535) throw new IllegalArgumentException("Invalid embedding dimensions");
        foundryEndpoint = foundryEndpoint.replaceAll("/$", "");
    }
}
