package com.example.oracle.azure.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
class DocumentIngestion {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private final VectorStore store;
    private final DemoSettings settings;
    DocumentIngestion(VectorStore store, DemoSettings settings) { this.store = store; this.settings = settings; }

    Map<String, Object> ingest(String fileName, byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("File must be between 1 byte and 2 MiB");
        String source = safeName(fileName);
        String text;
        if (source.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            try (var pdf = Loader.loadPDF(bytes)) {
                if (pdf.getNumberOfPages() > 100) throw new IllegalArgumentException("At most 100 PDF pages");
                text = new PDFTextStripper().getText(pdf);
            }
        } else if (source.toLowerCase(Locale.ROOT).matches(".*\\.(md|txt)$")) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else throw new IllegalArgumentException("Use a PDF, Markdown, or text document");
        if (text.isBlank() || text.length() > 200_000) throw new IllegalArgumentException("Document must contain 1–200000 text characters; scanned PDFs require OCR first");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var documents = chunks(source, text, digest, settings.embeddingDeployment());
        // Small batches keep provider requests bounded. IDs make retrying the same upload idempotent.
        for (int start = 0; start < documents.size(); start += 8)
            store.add(documents.subList(start, Math.min(start + 8, documents.size())));
        return Map.of("source", source, "sha256", digest, "chunks", documents.size(), "storage", "OracleVectorStore",
                "embeddingDeployment", settings.embeddingDeployment());
    }

    static String safeName(String name) {
        String result = Objects.requireNonNullElse(name, "document.txt").replace('\\', '/');
        result = result.substring(result.lastIndexOf('/') + 1).replaceAll("[^a-zA-Z0-9._-]", "_");
        if (result.length() > 120 || result.isBlank()) throw new IllegalArgumentException("Invalid document filename");
        return result;
    }

    static List<Document> chunks(String source, String text, String hash, String deployment) {
        var documents = new ArrayList<Document>();
        int number = 0;
        for (int start = 0; start < text.length(); start += 1600) {
            int end = Math.min(start + 1800, text.length());
            String id = UUID.nameUUIDFromBytes((source + ":" + hash + ":" + deployment + ":" + number).getBytes(StandardCharsets.UTF_8)).toString();
            documents.add(new Document(id, text.substring(start, end), Map.of("source", source, "sha256", hash,
                    "chunk", number++, "embeddingDeployment", deployment)));
            if (end == text.length()) break;
        }
        return documents;
    }
}
