# Java document RAG

This first Java slice implements PDF/Markdown/text ingestion, chunking, Foundry
embeddings, Oracle similarity search and streamed answers with source excerpts.
It is not a line-for-line port of the .NET sample: conversations are stateless,
there are no generated follow-up suggestions, and ingestion is an explicit upload
rather than an agent-selected tool.

## Database and model setup

1. Use a **dedicated demo schema**, not ADMIN or an existing inventory schema.
   The schema owner needs `CREATE SESSION`, `CREATE TABLE` and a bounded tablespace
   quota for first-run setup. The application operates only on
   `FOUNDRY_RAG_DOCUMENTS`; it never drops an existing table.
2. Set `DB_URL` to your EZConnect+ service URL, `DB_USERNAME`, and `DB_PASSWORD`.
   For mTLS add the appropriate wallet settings outside Git. No `tnsnames.ora`
   alias is needed. Leave TLS certificate verification enabled.
3. Set `FOUNDRY_ENDPOINT` to the HTTPS resource origin, without `/openai/v1`.
   Set `FOUNDRY_CHAT_DEPLOYMENT` and `FOUNDRY_EMBEDDING_DEPLOYMENT` to actual
   deployment names. The client appends the v1 chat/embedding paths.
4. Use `az login` locally and a principal authorized to call those deployments,
   or set `FOUNDRY_API_KEY`. In Azure, `DefaultAzureCredential` can use managed
   identity. This authenticates to **Foundry**, not automatically to Oracle.
5. Choose a random `DEMO_API_TOKEN` of at least 32 characters. The UI uses this
   token for both upload and chat; it is held in memory, not browser storage.
6. On the first start set `INITIALIZE_SCHEMA=true`; subsequently leave it false.
   For a separate runtime account, grant only the needed SELECT/INSERT/UPDATE
   privileges on this table and make it resolvable under that account. The
   opt-in integration test also needs DELETE for its own test chunks.

The default exact cosine search needs no approximate vector index for this small
demo. Match `EMBEDDING_DIMENSIONS` to the deployment. Do not switch embedding
models underneath an existing deployment name: use a new dedicated table/schema
and re-embed the documents. Retrieval filters by embedding deployment name.

## Run

From the repository root, after `bash build.sh` and exporting `.env`:

```bash
java -jar rag-chat-java/target/rag-chat-java-0.1.0-SNAPSHOT.jar
```

Open http://127.0.0.1:8080. Alternatively:

```bash
curl --fail-with-body -H "Authorization: Bearer $DEMO_API_TOKEN" \
  -F file=@samples/transfer-policy.md http://127.0.0.1:8080/api/documents
curl --fail-with-body -N -H "Authorization: Bearer $DEMO_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"question":"What checks are needed before recommending a transfer?"}' \
  http://127.0.0.1:8080/api/chat
```

SSE JSON events are `sources`, `token`, `done`, or `error`. The sources event
contains the request ID, deployment names, Oracle document IDs, hashes and
excerpts. Empty retrieval does not call the chat model. Retrieval/inference
failure emits `error` without a fabricated replacement answer.

Uploads are bounded to 2 MiB, 100 PDF pages and 200,000 extracted characters.
Scanned PDFs require OCR before upload. Chunks are 1,800 characters with 200
characters of overlap. IDs include filename, content hash, deployment and chunk
number: repeating the same upload upserts those IDs. Changed files are retained
as separate versions; the demo does not silently delete earlier versions. A
failed multi-batch upload may leave some chunks; retrying the same file is safe.

## Prove the live path

```bash
# Calls paid Foundry deployments and writes uniquely named test chunks.
# Requires existing table, credentials and network connectivity; deletes only those test IDs.
RUN_LIVE_TESTS=true mvn -pl rag-chat-java test -Dtest=LiveOracleFoundryTest
```

This test checks an unpredictable document code through ingestion, Oracle
retrieval and the streamed model answer. To inspect retrieved IDs independently,
query `FOUNDRY_RAG_DOCUMENTS` using SQL Developer and compare its content/hash with
the evidence panel. Enable database auditing through your normal administrative
process if server-side audit evidence is required. An on-screen label alone is
not proof of a database query.

## Security and limitations

This is a single-user demo, not a multi-tenant document service. All holders of
the demo token share the same corpus and upload capability. Before external
deployment add enterprise user authentication, per-document authorization,
separate ingestion permissions, rate limits, upload isolation, TLS and auditing.
PDF parsing is not a hardened untrusted-upload sandbox. Do not expose it to the
public internet as-is. Do not upload secrets or sensitive documents without
approval for both Oracle storage and Foundry processing.
