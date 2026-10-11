package com.example.oracle.azure.agent;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AgentTests {
    @Test void sharedToolkitDrivesAllowlist() {
        assertThat(AgentSpec.allowedTools()).containsExactly("list-rag-chunks", "list-rag-documents", "read-rag-chunk");
    }
    @Test void payloadRequiresApprovalAndHasNoSearchOrWriteTools() throws Exception {
        var body = FoundryClient.JSON.valueToTree(AgentSpec.body("test", "model", "https://mcp.example.com/mcp", "authenticated-connection"));
        var tool = body.path("definition").path("tools").get(0);
        assertThat(tool.path("require_approval").asText()).isEqualTo("always");
        assertThat(tool.path("project_connection_id").asText()).isEqualTo("authenticated-connection");
        assertThat(tool.path("allowed_tools").size()).isEqualTo(3);
        assertThat(body.toString()).doesNotContain("web_search", "write-query", "approve-inventory-transfer");
    }
    @Test void refusesInsecureEndpointOrMissingConnection() {
        assertThatThrownBy(() -> AgentSpec.body("test", "m", "http://mcp.example.com", "c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentSpec.body("test", "m", "https://mcp.example.com", "")).isInstanceOf(IllegalArgumentException.class);
    }
    static JsonNode call(String server, String tool, String arguments) {
        return FoundryClient.JSON.valueToTree(Map.of("server_label", server, "name", tool, "arguments", arguments));
    }
    @Test void approvalsValidateServerToolAndArguments() {
        assertThat(AgentMain.allowed(call(AgentSpec.LABEL, "list-rag-documents", "{}"))).isTrue();
        assertThat(AgentMain.allowed(call("other", "list-rag-documents", "{}"))).isFalse();
        assertThat(AgentMain.allowed(call(AgentSpec.LABEL, "write-query", "{}"))).isFalse();
        assertThat(AgentMain.allowed(call(AgentSpec.LABEL, "list-rag-documents", "{\"sql\":\"select 1\"}"))).isFalse();
        assertThat(AgentMain.allowed(call(AgentSpec.LABEL, "list-rag-chunks", "{\"source\":\"guide.md\"}"))).isTrue();
        assertThat(AgentMain.allowed(call(AgentSpec.LABEL, "list-rag-chunks", "{\"source\":\"../secret\"}"))).isFalse();
        assertThat(AgentMain.allowed(call(AgentSpec.LABEL, "read-rag-chunk", "{\"chunkId\":\"00000000-0000-0000-0000-000000000000\"}"))).isTrue();
        assertThat(AgentMain.allowed(call(AgentSpec.LABEL, "read-rag-chunk", "invalid"))).isFalse();
    }
    @Test void toolkitSqlCatalogMatchesFrameworkAndOnlyContainsBoundedReads() throws Exception {
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of("tools.yaml"))) {
            Map<String,Object> config = new Yaml().load(input);
            Map<String,Map<String,Object>> tools = (Map<String,Map<String,Object>>) config.get("tools");
            assertThat(tools.keySet()).containsExactlyInAnyOrderElementsOf(AgentSpec.allowedTools());
            tools.forEach((name, tool) -> {
                String sql = tool.get("statement").toString();
                assertThat(sql).startsWith("SELECT ").doesNotContain("DELETE", "INSERT", "UPDATE", "DROP");
                assertThat(sql).contains("FOUNDRY_RAG_DOCUMENTS");
                assertThat(sql.contains("FETCH FIRST 50") || sql.contains("id = :chunkId")).isTrue();
            });
        }
    }
    @Test void httpAdapterSendsPayloadAndDoesNotLeakErrorBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new AtomicReference<String>();
        server.createContext("/api/projects/test/agents", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-token");
            byte[] response = "{\"name\":\"test-agent\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.createContext("/api/projects/test/fail", exchange -> {
            byte[] response = "private token upstream detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var client = new FoundryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/projects/test"), () -> "test-token");
            assertThat(client.request("POST", "/agents?api-version=v1", Map.of("name", "test-agent")).path("name").asText()).isEqualTo("test-agent");
            assertThat(seen.get()).contains("test-agent");
            assertThatThrownBy(() -> client.request("GET", "/fail", null)).hasMessageContaining("403").hasMessageNotContaining("private token");
        } finally { server.stop(0); }
    }
}
