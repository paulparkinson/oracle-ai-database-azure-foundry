package com.example.oracle.azure.rag;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.oracle.OracleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;

@Configuration
class AiConfiguration {
    @Bean OpenAiApi api(DemoSettings settings) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        var rest = RestClient.builder().requestFactory(requestFactory);
        var web = WebClient.builder();
        // One credential/provider instance; its token cache is reused across requests.
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            var credential = new DefaultAzureCredentialBuilder().build();
            var scope = new TokenRequestContext().addScopes("https://cognitiveservices.azure.com/.default");
            rest.requestInterceptor((request, body, execution) -> {
                var token = credential.getToken(scope).block(Duration.ofSeconds(30));
                if (token == null) throw new IllegalStateException("Entra token acquisition failed");
                request.getHeaders().setBearerAuth(token.getToken());
                return execution.execute(request, body);
            });
            web.filter((request, next) -> credential.getToken(scope).timeout(Duration.ofSeconds(30))
                    .flatMap(token -> next.exchange(org.springframework.web.reactive.function.client.ClientRequest.from(request)
                            .headers(headers -> headers.setBearerAuth(token.getToken())).build())));
        }
        return OpenAiApi.builder().baseUrl(settings.foundryEndpoint())
                .apiKey(settings.apiKey() == null || settings.apiKey().isBlank() ? "entra-token-set-by-interceptor" : settings.apiKey())
                .completionsPath("/openai/v1/chat/completions").embeddingsPath("/openai/v1/embeddings")
                .restClientBuilder(rest).webClientBuilder(web).build();
    }

    @Bean OpenAiEmbeddingModel embeddings(OpenAiApi api, DemoSettings settings) {
        return new OpenAiEmbeddingModel(api, org.springframework.ai.document.MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(settings.embeddingDeployment()).dimensions(settings.dimensions()).build());
    }

    @Bean OpenAiChatModel chatModel(OpenAiApi api, DemoSettings settings) {
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(settings.chatDeployment()).build()).build();
    }

    @Bean OracleVectorStore vectorStore(JdbcTemplate jdbc, OpenAiEmbeddingModel embeddings, DemoSettings settings) {
        return OracleVectorStore.builder(jdbc, embeddings).tableName("FOUNDRY_RAG_DOCUMENTS")
                .dimensions(settings.dimensions()).initializeSchema(settings.initializeSchema())
                .removeExistingVectorStoreTable(false).build();
    }
}
