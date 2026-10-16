# sureai — English Documentation

> **Bilingual docs.** These pages are the English reading track. The authoritative
> Chinese source for every topic lives one directory up (`../<topic>.md`); each page
> below links to its Chinese detail page for the long-form reference. Nothing on this
> page replaces the Chinese docs — it is a faster path for readers who prefer English.

**sureai** is a zero-third-party-dependency Java toolkit for LLM integration. One
independent module and one static utility class per mainstream AI platform, with strict
module isolation — pull in only what you use. JDK 21+.

## 30-minute fast path

New here? Follow this order and you will have a first working call in minutes:

1. **[Quick Start](./quickstart.md)** — BOM dependency, a minimal chat, streaming, and how to switch platforms.
2. **[CLI](./cli.md)** — no Java at all: chat / stream / local-doc RAG from the terminal.
3. **[Cookbook scenarios](../COOKBOOK.md)** — copy-paste recipes (Chinese; code is universal).

Then branch into the capability you need.

## By topic

| Topic | English page | Chinese detail |
|---|---|---|
| Quick Start (5 minutes) | [quickstart.md](./quickstart.md) | [../COOKBOOK.md](../COOKBOOK.md) |
| Platform matrix (23 platforms, baseUrl, models) | [platforms.md](./platforms.md) | [../capabilities.md](../capabilities.md) |
| RAG pipeline | [rag.md](./rag.md) | [../rag.md](../rag.md) |
| Document ingest (loaders / parsers, v2.6.0) | [ingest.md](./ingest.md) | [../ingest.md](../ingest.md) |
| Agent orchestration (ReAct + production capabilities) | [agent.md](./agent.md) | [../agent.md](../agent.md), [../agent-advanced.md](../agent-advanced.md) |
| Declarative orchestration (AiService + Advisor chain + SemanticCache) | [framework.md](./framework.md) | [../framework.md](../framework.md) |
| AI Gateway (routing / failover / key pool / tenant quota) | [gateway.md](./gateway.md) | [../gateway.md](../gateway.md) |
| MCP Server (expose sureai as a Model Context Protocol server) | [mcp-server.md](./mcp-server.md) | [../mcp-server.md](../mcp-server.md) |
| Command-line tool | [cli.md](./cli.md) | [../cli.md](../cli.md) |
| Structured output (JSON / records) | [structured-output.md](./structured-output.md) | [../structured-output.md](../structured-output.md) |
| Observability (metrics / retry / Micrometer / OpenTelemetry) | [observability.md](./observability.md) | [../observability.md](../observability.md) |
| Supply-chain trust (GPG / SBOM / SLSA / CVE / fuzz) | [trust.md](./trust.md) | [../trust.md](../trust.md) |

## Where to start

- **Maven coordinates & the full platform table**: see the English [README](../../README.en.md) at the repo root.
- **Full Chinese documentation index**: [../../README.md](../../README.md).
