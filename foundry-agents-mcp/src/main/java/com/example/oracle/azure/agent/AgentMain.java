package com.example.oracle.azure.agent;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.*;

public final class AgentMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Commands: spec | create | ask <agent-name> <question> | approve <agent-name> <response-id>");
            return;
        }
        String command = args[0];
        if (command.equals("spec") || command.equals("create")) {
            if (args.length != 1) throw new IllegalArgumentException("No positional parameters for spec/create");
            var body = AgentSpec.body("oracle-foundry-demo-" + UUID.randomUUID(), required("FOUNDRY_CHAT_DEPLOYMENT"),
                    required("MCP_SERVER_URL"), required("MCP_PROJECT_CONNECTION_NAME"));
            if (command.equals("spec")) { print(body); return; }
            print(client().request("POST", "/agents?api-version=v1", body));
            return;
        }
        if (!(command.equals("ask") || command.equals("approve")) || args.length != 3)
            throw new IllegalArgumentException("Use ask <agent-name> <quoted-question> or approve <agent-name> <response-id>");
        if (!args[1].matches("oracle-foundry-demo-[a-f0-9-]{36}")) throw new IllegalArgumentException("Use the exact demo agent name returned by create");
        var client = client();
        if (command.equals("ask")) {
            if (args[2].isBlank() || args[2].length() > 2000) throw new IllegalArgumentException("Question must contain 1–2000 characters");
            print(client.request("POST", "/openai/v1/responses", Map.of("agent", Map.of("type", "agent_reference", "name", args[1]), "input", args[2])));
        } else {
            if (!args[2].matches("[a-zA-Z0-9_-]{1,200}")) throw new IllegalArgumentException("Invalid response ID");
            var previous = client.request("GET", "/openai/v1/responses/" + args[2], null);
            var decisions = new ArrayList<Map<String,Object>>();
            var input = new BufferedReader(new InputStreamReader(System.in));
            for (JsonNode output : previous.path("output")) {
                if (!output.path("type").asText().equals("mcp_approval_request")) continue;
                boolean allowed = allowed(output);
                System.out.printf("Server: %s; tool: %s; arguments: %s%n", output.path("server_label").asText(),
                        output.path("name").asText(), output.path("arguments").asText());
                boolean approve = false;
                if (allowed) { System.out.print("Approve this read? Type yes: "); approve = "yes".equals(input.readLine()); }
                else System.out.println("Denied: server, tool, or argument schema does not match this demo's allowlist.");
                decisions.add(Map.of("type", "mcp_approval_response", "approval_request_id", output.path("id").asText(), "approve", approve));
            }
            if (decisions.isEmpty()) throw new IllegalStateException("No pending MCP approvals in that response");
            print(client.request("POST", "/openai/v1/responses", Map.of("agent", Map.of("type", "agent_reference", "name", args[1]),
                    "previous_response_id", args[2], "input", decisions)));
        }
    }

    static boolean allowed(JsonNode call) {
        if (!call.path("server_label").asText().equals(AgentSpec.LABEL) || !AgentSpec.allowedTools().contains(call.path("name").asText())) return false;
        try {
            var arguments = FoundryClient.JSON.readTree(call.path("arguments").asText("{}"));
            if (!arguments.isObject()) return false;
            return switch (call.path("name").asText()) {
                case "list-rag-documents" -> arguments.isEmpty();
                case "list-rag-chunks" -> arguments.size() == 1 && arguments.path("source").isTextual()
                        && arguments.path("source").asText().matches("[a-zA-Z0-9._-]{1,120}");
                case "read-rag-chunk" -> arguments.size() == 1 && arguments.path("chunkId").isTextual()
                        && arguments.path("chunkId").asText().matches("[a-fA-F0-9-]{36}");
                default -> false;
            };
        } catch (Exception error) { return false; }
    }

    static FoundryClient client() {
        var endpoint = AgentSpec.requireHttps(required("FOUNDRY_PROJECT_ENDPOINT"));
        if (!(endpoint.getHost().endsWith(".ai.azure.com") || endpoint.getHost().endsWith(".services.ai.azure.com"))
                || !endpoint.getPath().matches("/api/projects/[a-zA-Z0-9._-]+/?") || endpoint.getQuery() != null)
            throw new IllegalArgumentException("Expected an Azure Foundry project endpoint ending in /api/projects/<project>");
        var credential = new DefaultAzureCredentialBuilder().build();
        var context = new TokenRequestContext().addScopes("https://ai.azure.com/.default");
        return new FoundryClient(endpoint, () -> {
            var token = credential.getToken(context).block(Duration.ofSeconds(30));
            if (token == null) throw new IllegalStateException("Entra token acquisition failed");
            return token.getToken();
        });
    }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }
    static void print(Object value) throws Exception { System.out.println(FoundryClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
}
