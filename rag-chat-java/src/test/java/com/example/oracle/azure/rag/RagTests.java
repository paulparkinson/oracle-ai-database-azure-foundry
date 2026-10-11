package com.example.oracle.azure.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

class RagTests {
    static DemoSettings settings() { return new DemoSettings("t".repeat(40), "https://example.openai.azure.com", "", "chat", "embed", 1536, false); }
    @Test void chunksAreBoundedOverlappingAndStable() {
        String text = "abcdef".repeat(1000);
        var first = DocumentIngestion.chunks("guide.md", text, "hash", "embed");
        var second = DocumentIngestion.chunks("guide.md", text, "hash", "embed");
        assertThat(first).hasSize(4);
        assertThat(first.get(0).getText()).hasSize(1800);
        assertThat(first.get(1).getText().substring(0, 200)).isEqualTo(first.get(0).getText().substring(1600));
        assertThat(first.stream().map(Document::getId)).containsExactlyElementsOf(second.stream().map(Document::getId).toList());
        assertThat(first.getLast().getText()).endsWith(text.substring(text.length() - 200));
    }
    @Test void differentDocumentVersionHasDifferentIds() {
        assertThat(DocumentIngestion.chunks("guide.md", "abc", "one", "embed").getFirst().getId())
                .isNotEqualTo(DocumentIngestion.chunks("guide.md", "abc", "two", "embed").getFirst().getId());
    }
    @Test void ingestionUsesOracleStoreAndMetadata() throws Exception {
        var store = mock(VectorStore.class);
        var result = new DocumentIngestion(store, settings()).ingest("../../policy.md", "Never execute transfers without approval.".getBytes(StandardCharsets.UTF_8));
        assertThat(result.get("source")).isEqualTo("policy.md");
        assertThat(result.get("sha256").toString()).hasSize(64);
        verify(store).add(argThat(docs -> docs.size() == 1 && docs.getFirst().getMetadata().get("embeddingDeployment").equals("embed")));
    }
    @Test void rejectsUnsupportedAndEmptyUploads() {
        var store = mock(VectorStore.class); var ingestion = new DocumentIngestion(store, settings());
        assertThatThrownBy(() -> ingestion.ingest("file.exe", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingestion.ingest("file.md", new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingestion.ingest("file.md", new byte[DocumentIngestion.MAX_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
    @Test void pdfTextIsExtracted() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(); pdf.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(30, 700); content.showText("Approval is required before transfer."); content.endText();
            }
            pdf.save(bytes);
        }
        var store = mock(VectorStore.class);
        new DocumentIngestion(store, settings()).ingest("policy.pdf", bytes.toByteArray());
        verify(store).add(argThat(docs -> docs.getFirst().getText().contains("Approval is required")));
    }
    @Test void retrievalFailureDoesNotCallModel() {
        var store = mock(VectorStore.class); var model = mock(ChatModel.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("private upstream detail"));
        var events = new RagService(store, model, settings()).answer("Question").collectList().block();
        assertThat(events).hasSize(1); assertThat(events.getFirst().type()).isEqualTo("error");
        assertThat(events.toString()).doesNotContain("private upstream detail"); verifyNoInteractions(model);
    }
    @Test void noEvidenceDoesNotCallModel() {
        var store = mock(VectorStore.class); var model = mock(ChatModel.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var events = new RagService(store, model, settings()).answer("Question").collectList().block();
        assertThat(events.stream().map(RagService.Event::type)).containsExactly("sources", "token", "done");
        verifyNoInteractions(model);
    }
    @Test void answersIncludeRetrievedIdsBeforeTokens() {
        var store = mock(VectorStore.class); var model = mock(ChatModel.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("chunk-id", "Approval required.", Map.of("source", "policy.md", "sha256", "abc"))));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Approval required [S1]."))))));
        var events = new RagService(store, model, settings()).answer("When?").collectList().block();
        assertThat(events.stream().map(RagService.Event::type)).containsExactly("sources", "token", "done");
        assertThat(events.getFirst().data().toString()).contains("chunk-id", "policy.md");
        verify(store).similaritySearch(argThat((SearchRequest request) -> request.getTopK() == 5 && request.getFilterExpression() != null));
        verify(model).stream(argThat((Prompt prompt) -> prompt.getContents().contains("Approval required.") && prompt.getContents().contains("[S1]")));
    }
    @Test void inferenceFailureIsNotPresentedAsSuccessfulAnswer() {
        var store = mock(VectorStore.class); var model = mock(ChatModel.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("text")));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("bad key")));
        var events = new RagService(store, model, settings()).answer("Question").collectList().block();
        assertThat(events.stream().map(RagService.Event::type)).containsExactly("sources", "error");
    }
    @Test void authAndInputValidationAreEnforced() throws Exception {
        var controller = new RagController(mock(DocumentIngestion.class), mock(RagService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).addFilter(new ApiSecurity(settings())).build();
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"question\":\"hi\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat").header("Authorization", "Bearer " + settings().apiToken()).contentType("application/json").content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "a.md", "text/plain", new byte[]{1})))
                .andExpect(status().isUnauthorized());
    }
    @Test void endpointValidationRejectsPlaintextAndEmbeddedCredentials() {
        assertThatThrownBy(() -> new DemoSettings("t".repeat(40), "http://host", "", "c", "e", 3, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoSettings("t".repeat(40), "https://user:secret@host", "", "c", "e", 3, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoSettings("short", "https://host", "", "c", "e", 3, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
