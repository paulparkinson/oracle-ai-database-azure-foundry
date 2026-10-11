# Foundry agent → existing Oracle MCP Toolkit

The Java CLI uses **Foundry Agent Service REST APIs**, not Microsoft Agent
Framework. Tool selection and approvals are constrained by definitions loaded
through the shared fullstack toolkit. SQL runs in the existing Oracle MCP Toolkit.

This demo reads the RAG document catalog and chunks. It is **not** the managed
Oracle AI Database Agent from the GCP demo, does not call that agent, and does not
provide semantic search through MCP. Semantic retrieval is implemented by the
sibling RAG application. No inventory transfer tools are enabled.

## 1. Prepare the existing MCP server

First ingest the sample documents using `rag-chat-java`. Create a separate
database principal with `CREATE SESSION` and **SELECT only** on the RAG table.
Make `FOUNDRY_RAG_DOCUMENTS` resolve to the owner's table for this principal
(for example, a private synonym created by the administrator). It must have no
DDL/DML privileges. Export `DB_URL`, `MCP_DB_USERNAME`, `MCP_DB_PASSWORD`.

The repository's `build.sh` downloads a pinned shared toolkit under `.deps/`.
Build its existing server:

```bash
mvn -f .deps/oracle-ai-database-fullstack-toolkit/upstream/oracle-db-mcp-java-toolkit/pom.xml package
python3 scripts/check-mcp-catalog.py
```

The catalog check starts the actual server over stdio, checks the three exposed
tool schemas, and stops it. It deliberately uses non-working database credentials
and does not execute SQL. Keep `parameters: []` for the parameterless catalog
tool: the pinned server requires an explicit list during tool registration.

Configure that server with this repository's `foundry-agents-mcp/tools.yaml`,
and **always** restrict enabled tools:

```text
-DconfigFile=/absolute/path/to/foundry-agents-mcp/tools.yaml
-Dtools=foundry-document-reads
-Dtransport=http
-DenableAuthentication=true
```

Do not omit `-Dtools`: the upstream default enables additional tools. Use the
upstream [TLS/OAuth deployment instructions](https://github.com/paulparkinson/oracle-ai-database-fullstack-toolkit/blob/e8e067400ff93d275cb3b6c005c835f87d55e835/upstream/oracle-db-mcp-java-toolkit/README.md)
to expose `/mcp` through an authenticated HTTPS endpoint reachable from Foundry.
Do not expose an unauthenticated local server publicly. Network and authentication
configuration is deployment-specific; no endpoint is provisioned by this sample.

Only these fixed, bounded SQL tools are enabled:

| Tool | Result |
| --- | --- |
| `list-rag-documents` | Up to 50 source/hash versions with chunk counts |
| `list-rag-chunks` | Up to 50 chunk IDs for one bound source filename |
| `read-rag-chunk` | One chunk selected by its bound ID |

## 2. Connect Foundry

In your Foundry project, create an authenticated **remote MCP tool connection**
to this HTTPS server, using the supported authentication mode matching its
deployment. Store credentials in the project connection, not Java source or the
MCP URL. Restrict project permissions to approved operators. If your project
requires a toolbox, expose these same tools through a toolbox and configure
its authenticated endpoint/connection instead.

Export the values in the root `.env.example`:

- `FOUNDRY_PROJECT_ENDPOINT`: project URL ending in `/api/projects/<project>`.
- `FOUNDRY_CHAT_DEPLOYMENT`: a tool-capable model deployment in that project.
- `MCP_SERVER_URL`: authenticated server or toolbox HTTPS endpoint.
- `MCP_PROJECT_CONNECTION_NAME`: the matching Foundry project connection.

Authenticate with `az login`. The Java client requests Entra tokens for
`https://ai.azure.com/.default`. You need permissions to create/use agents and
use the project connection. Model deployment, role assignment and network
administration require separate permissions if resources are not already present.

## 3. Run

From the repository root after `bash build.sh`:

```bash
agent_jar=foundry-agents-mcp/target/foundry-agents-mcp-0.1.0-SNAPSHOT.jar
java -jar "$agent_jar" spec       # inspect configuration; no Azure calls
java -jar "$agent_jar" create     # creates a uniquely named demo agent
# Copy the returned name into agent_name:
agent_name='oracle-foundry-demo-<returned-UUID>'
java -jar "$agent_jar" ask "$agent_name" 'List the documents indexed in Oracle. Return a concise table.'
# If output contains mcp_approval_request, copy the top-level response id:
java -jar "$agent_jar" approve "$agent_name" '<response-id>'
```

The approval command retrieves the pending requests from Foundry, displays
the actual tool and arguments, validates them against the allowlist/schema,
and asks you to type `yes`. Unknown servers/tools or malformed arguments are
denied. Approval is never inferred from model prose. If another approval is
returned, repeat with the new response ID.

Next ask:

```bash
java -jar "$agent_jar" ask "$agent_name" \
  'Read transfer-policy.md from Oracle and summarize its approval requirements. Cite the source and chunk IDs.'
```

Compare the actual `mcp_call` output, source hashes and chunk IDs with the Oracle
table. A generated answer or a skill-loading message is not evidence of a tool
call. Record the exact created agent name; remove **only that demo agent** in
the Foundry portal when finished. The CLI intentionally does not delete existing
project resources or change connections.

## Tested versus pending

Local tests verify HTTP request/response handling, metadata generation, SQL
catalog consistency, tool restrictions and approval validation. Live Foundry
agent creation, project-connection authentication, MCP calls and Oracle results
still require an authenticated Azure session and deployed resources. No live
success is claimed by local contract tests.
