# Oracle AI Database with Microsoft Foundry on Azure

Java examples combining Foundry models and agents with Oracle AI Database.
Microsoft Foundry was formerly called Azure AI Foundry. These examples use
**Spring AI and the Foundry Agent Service REST API from Java**, not a Java
Microsoft Agent Framework SDK (that framework supports .NET and Python).

## Implemented

| Project | What it does |
| --- | --- |
| [rag-chat-java](rag-chat-java/README.md) | Upload PDF/Markdown/text, generate Foundry embeddings, store/search vectors in Oracle, and stream grounded answers with inspectable document evidence. |
| [foundry-agents-mcp](foundry-agents-mcp/README.md) | Create and call a Foundry agent connected to the existing Oracle Java MCP Toolkit. Restrict it to three document-read tools and require explicit approval for each call. |

```text
Browser → Java RAG service → Foundry embeddings
                          → OracleVectorStore / Oracle AI Database
                          → Foundry chat → streamed answer + retrieved passages

Java agent client → Foundry Agent Service → authenticated Oracle MCP Toolkit
                                         → bounded SQL → same Oracle document table
```

The [shared fullstack toolkit](https://github.com/paulparkinson/oracle-ai-database-fullstack-toolkit)
supplies the agent demo's tool registry and enabled-surface definitions. Its pinned
vendored [Oracle MCP Toolkit](https://github.com/oracle/mcp/tree/main/src/oracle-db-mcp-java-toolkit)
executes SQL; this repository does not fork that execution engine.
The RAG demo uses Spring AI's Oracle vector-store integration directly.

## Validation status — October 11, 2026

- Both Java modules compile; **17 automated unit and HTTP contract tests pass**.
- Tests cover chunking/PDF extraction, idempotent document IDs, evidence streaming,
  authentication, failure without fallback, MCP allowlists and approval validation.
- The real pinned Oracle MCP server builds and its stdio discovery exposes exactly
  the three configured document-read tools. The packaged RAG UI starts, loads in
  Chrome, and rejects unauthenticated API requests with HTTP 401.
- **Live Azure/Oracle verification is not yet complete.** The existing Azure CLI
  session expired; resource discovery returned `AADSTS700082`. No new Azure
  resources, paid deployments, database schemas, or live agents have been created.
- An opt-in live test embeds a unique document, retrieves its Oracle chunk, asks
  Foundry for its verification code, then deletes only those test chunks.
- Unit tests use explicitly mocked dependencies; their success is not evidence of
  a live database call. No production mock mode exists.

## Build and run

Prerequisites: JDK 21, Maven 3.9+, Git, Oracle AI Database with native VECTOR
support, and Foundry chat + embedding deployments. For Agent Service, also provide
a Foundry project and an authenticated MCP endpoint reachable from that project.

```bash
git clone https://github.com/paulparkinson/oracle-ai-database-azure-foundry.git
cd oracle-ai-database-azure-foundry
bash build.sh
cp .env.example .env
# Edit .env; keep credentials outside Git. Generate DEMO_API_TOKEN with: openssl rand -hex 32
set -a
source .env
set +a
az login
# First run only, in the dedicated demo schema:
INITIALIZE_SCHEMA=true java -jar rag-chat-java/target/rag-chat-java-0.1.0-SNAPSHOT.jar
```

Open **http://127.0.0.1:8080**, enter the demo access token, upload the two files
under `samples/`, and ask:

1. `What checks are required before recommending an inventory transfer?`
2. `Can returned items be transferred before inspection? Cite the policy.`
3. `What is the current stock of SKU-500?` — the policy documents cannot establish
   this; the answer should acknowledge missing evidence, not invent inventory.

Use the module READMEs for database privileges, authentication, MCP setup and
commands. Restart normally without `INITIALIZE_SCHEMA=true` after initial setup.
The app binds to loopback by default. No VM is inherently required: Java can run
on a workstation or a suitable Azure container host. Remote sharing requires TLS,
enterprise authentication and network access to Oracle; this commit does not
provision an Azure deployment.

## Models, identity and data boundaries

- Model **deployment names** are configuration, not hardcoded claims about what
  is deployed in your account. Use an embedding deployment compatible with the
  configured dimensions (for example, `text-embedding-3-small`, 1536 dimensions).
- Foundry authentication uses one reusable `DefaultAzureCredential`; optionally
  provide `FOUNDRY_API_KEY` for the RAG model endpoint. Agent Service uses Entra.
- Oracle JDBC uses an EZConnect+ URL and a dedicated demo account. This initial
  implementation does **not** claim passwordless Oracle login. Existing
  [Entra/OCI JDBC examples](https://github.com/paulparkinson/oracle-db-examples/tree/main/java/jdbc-token-auth)
  provide the basis for a later identity-specific example.
- Uploaded text goes to Foundry for embeddings; retrieved excerpts go to the chat
  deployment. Upload only content approved for those services.
- Source hashes and chunk IDs establish which passages were retrieved. They do
  not prove that every generated statement faithfully reflects those passages.

## Next projects—not implemented in this initial slice

- `supply-chain/`: inventory list → graph → spatial map → transfer review, using
  the shared toolkit. Verify host support before promising embedded MCP Apps/A2UI.
- `nl2sql/`: model-generated SQL with a constrained execution policy; distinguish
  this from database-side Select AI.
- `entra-identity/`: dedicated passwordless JDBC and workload-identity validation.

The existing AWS/GCP demos and shared toolkit source are unchanged.

## References

- [Original Oracle .NET chat sample](https://github.com/oracle/dotnet-db-samples/tree/e5b56a0e531de68a722f1ed818ad8567f136d0d6/samples/ai-vector/chat)
- [Oracle Spring AI vector store](https://docs.oracle.com/en/database/oracle/oracle-database/26/aintg/spring-ai-integration-guide/oracle-vector-store.html)
- [Foundry MCP integration](https://learn.microsoft.com/en-us/azure/foundry/agents/how-to/tools/model-context-protocol)
- [Microsoft Agent Framework supported languages](https://learn.microsoft.com/en-us/agent-framework/support/faq)
