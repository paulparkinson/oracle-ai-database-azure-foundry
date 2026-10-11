package com.example.oracle.azure.rag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
class RagController {
    record Question(@NotBlank @Size(max=2000) String question) {}
    private final DocumentIngestion ingestion;
    private final RagService rag;
    RagController(DocumentIngestion ingestion, RagService rag) { this.ingestion = ingestion; this.rag = rag; }
    @PostMapping(value="/documents", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String,Object> ingest(@RequestParam("file") MultipartFile file) throws Exception {
        return ingestion.ingest(file.getOriginalFilename(), file.getBytes());
    }
    @PostMapping(value="/chat", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<RagService.Event> chat(@Valid @RequestBody Question question) { return rag.answer(question.question()); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException error) { return Map.of("error", "Invalid document or request; check file type, size, and configuration."); }
}
