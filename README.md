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

## Validation status — October 12, 2026

- Both Java modules compile; **17 automated unit and HTTP contract tests pass**.
- Tests cover chunking/PDF extraction, idempotent document IDs, evidence streaming,
  authentication, failure without fallback, MCP allowlists and approval validation.
- The real pinned Oracle MCP server builds and its stdio discovery exposes exactly
  the three configured document-read tools. The packaged RAG UI starts, loads in
  Chrome, and rejects unauthenticated API requests with HTTP 401.
- **Live Foundry operator access verified:** Entra-authenticated embeddings returned
  1,536 dimensions from `text-embedding-3-small`; chat returned the requested
  `AZURE_CHAT_OK` response from `gpt-4.1-mini-2025-04-14`; agent listing succeeded.
  The Java build and database health check also passed again.
- **End-to-end RAG and agent/MCP verification remains incomplete.** The Azure VM
  now has a system-assigned managed identity, but granting that identity Foundry
  inference access is still denied by the operator's ABAC condition. Operator
  access does not automatically grant access to the deployed application.
- **Azure database verified:** Oracle AI Database 26ai Free `23.26.3.0.0` runs on
  the dedicated Azure VM. `FREEPDB1` is READ WRITE and a native cosine VECTOR
  distance query returned `0` for identical vectors. No existing `financialdb`
  was accessed. No live Foundry agent has been created.
- An opt-in live test embeds a unique document, retrieves its Oracle chunk, asks
  Foundry for its verification code, then deletes only those test chunks.
- Unit tests use explicitly mocked dependencies; their success is not evidence of
  a live database call. No production mock mode exists.

## Dedicated Azure deployment

Created in East US on October 11, 2026:

| Resource | Name / configuration |
| --- | --- |
| Resource group | `rg-pp-oracle-foundry-demo` |
| Foundry account | `pp-oracle-foundry-demo-20261011`, S0, system-assigned identity |
| Project | `oracle-java-demo` |
| Chat deployment | `demo-chat`: `gpt-4.1-mini`, version `2025-04-14` |
| Embedding deployment | `demo-embedding`: `text-embedding-3-small`, version `1`, 1536 dimensions |

Both deployments use regional **Standard**, capacity 10 each, not provisioned
throughput. Model usage incurs charges. The database VM below is separate from
these model deployments. No publicly exposed MCP endpoint has been created.
Successful provisioning is not an inference test.

Set these non-secret values in your private `.env`:

```properties
FOUNDRY_ENDPOINT=https://pp-oracle-foundry-demo-20261011.openai.azure.com
FOUNDRY_CHAT_DEPLOYMENT=demo-chat
FOUNDRY_EMBEDDING_DEPLOYMENT=demo-embedding
EMBEDDING_DIMENSIONS=1536
FOUNDRY_PROJECT_ENDPOINT=https://pp-oracle-foundry-demo-20261011.services.ai.azure.com/api/projects/oracle-java-demo
```

**Remaining administrator request:** grant **Cognitive Services OpenAI User**
to the system-assigned identity of `vm-oracle-demo-db`, principal
`de0f5ab7-68e1-4877-bd06-fea5576f02f5`, scoped only to the demo Foundry account:

```sh
az role assignment create \
  --assignee-object-id de0f5ab7-68e1-4877-bd06-fea5576f02f5 \
  --assignee-principal-type ServicePrincipal \
  --role 5e0bd9bd-7b93-4f28-af87-19fc36ad61bd \
  --scope /subscriptions/7080446f-ee76-4aa2-b9dd-c2625f63cab0/resourceGroups/rg-pp-oracle-foundry-demo/providers/Microsoft.CognitiveServices/accounts/pp-oracle-foundry-demo-20261011
```

This assignment was attempted on October 12 and rejected with
`AuthorizationFailed` for `Microsoft.Authorization/roleAssignments/write` (ABAC).
The operator's embedding, chat and agent-list permissions now work; those grants
do not need to be requested again. No stored operator tokens or account keys
were substituted for the VM identity.

Next: configure the dedicated database application schema, deploy the Java RAG
service on Azure, run its opt-in live Oracle/Foundry test, then deploy and validate
the authenticated MCP endpoint. Neither demo is yet verified end-to-end. This
deployment must not use an OCI/AWS/GCP database.

### Azure-only database

- VM: `vm-oracle-demo-db`, East US, `Standard_D2s_v5` (2 vCPU, 8 GiB), Ubuntu
  22.04, 64-GiB Standard SSD, in `rg-pp-oracle-foundry-demo`.
- Oracle Database Free container from Oracle Container Registry; the setup pins
  the verified `23.26.3.0.0` image digest. This is a self-managed demo, **not** Autonomous
  Database or a managed Oracle Database@Azure service.
- Persistent named volume: `oracle-demo-data`. The generated administrator
  password stays in the VM's Podman secret store; never print it or commit it.
- The listener binds only to `127.0.0.1:1521`. NSG `nsg-oracle-demo-db` denies
  **all inbound traffic**, including SSH. The public IP is for explicit outbound
  connectivity; it does not make the database publicly reachable. Administration
  uses Azure Run Command. The application is not yet deployed alongside it.
- Database version, READ WRITE PDB and native VECTOR SQL were verified on
  October 11, 2026. JDBC application credentials and end-to-end Foundry RAG are
  **not yet verified**; database readiness does not resolve the model RBAC blocker.
- VM, disk and public IP incur Azure charges. Deallocate the VM when unused;
  disks/public IP continue to incur charges. This demo has no backup/HA setup.

Run setup once, then verify (Azure rejects concurrent Run Commands):

```bash
az vm run-command invoke -g rg-pp-oracle-foundry-demo -n vm-oracle-demo-db \
  --command-id RunShellScript --scripts @scripts/setup-azure-database.sh
az vm run-command invoke -g rg-pp-oracle-foundry-demo -n vm-oracle-demo-db \
  --command-id RunShellScript --scripts @scripts/check-azure-database.sh
```

The verification prints the actual database version, PDB open mode, loopback
port mapping and native cosine vector distance (expected `0`). It does not test
Foundry inference or JDBC application credentials. Do not replace/remove the
existing container or its volume when rerunning setup.

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
enterprise authentication and network access to Oracle. Only the Foundry resources
listed above are provisioned; the application is not yet hosted in Azure.

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
