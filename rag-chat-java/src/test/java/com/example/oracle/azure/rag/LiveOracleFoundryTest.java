package com.example.oracle.azure.rag;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.vectorstore.VectorStore;

/** Opt-in: writes only uniquely named test chunks, calls paid Foundry endpoints, then deletes those chunks. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class LiveOracleFoundryTest {
    @Autowired DocumentIngestion ingestion;
    @Autowired RagService rag;
    @Autowired VectorStore store;
    @Autowired DemoSettings settings;
    @Test void freshNonceTravelsThroughEmbeddingOracleRetrievalAndChat() throws Exception {
        String nonce = UUID.randomUUID().toString();
        // Keep the answer out of the filename/question: it must be retrieved from Oracle.
        String source = "live-check-" + UUID.randomUUID() + ".md";
        String text = "The verification code for this unique document " + source + " is " + nonce + ".";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var ids = DocumentIngestion.chunks(source, text, hash, settings.embeddingDeployment()).stream().map(org.springframework.ai.document.Document::getId).toList();
        try {
            ingestion.ingest(source, bytes);
            var events = rag.answer("What is the verification code in " + source + "? Quote it and cite the document.").collectList().block(Duration.ofSeconds(120));
            assertThat(events).isNotNull();
            assertThat(events.stream().map(RagService.Event::type)).doesNotContain("error").contains("sources", "token", "done");
            assertThat(events.getFirst().data().toString()).contains(ids.getFirst(), hash);
            String answer = events.stream().filter(event -> event.type().equals("token")).map(event -> event.data().toString()).reduce("", String::concat);
            assertThat(answer).contains(nonce).contains("[S");
        } finally { store.delete(ids); }
    }
}
