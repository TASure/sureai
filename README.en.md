# sureai

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21+-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven Central](https://img.shields.io/badge/maven--central-1.4.0-lightgrey.svg)](https://central.sonatype.com/)
[![CI](https://img.shields.io/badge/CI-passing-brightgreen.svg)](.github/workflows/ci.yml)

**Zero third-party dependency Java LLM integration toolkit.** One independent module and static utility class per mainstream AI platform, with strict module-level isolation — pull in only what you need.

## How it differs from langchain4j / Spring AI

| Dimension | sureai | langchain4j | Spring AI |
|-----------|--------|-------------|-----------|
| Third-party deps | **Zero** (JDK + built-in JSON/HTTP only) | Heavy transitive chain | Tied to Spring ecosystem |
| Zero-config usage | **Static utility, one-line call** | Requires Builder wiring | Requires @Configuration + Beans |
| Chinese platform coverage | **All 23 platforms** (Baidu, Zhipu, Doubao, MiniMax, Hunyuan, Spark, etc.) | Partial | Partial |
| Module isolation | **Per-module zero-dep**, pull only what you need | Monolithic | Monolithic |
| JDK requirement | 21+ (records / pattern matching) | 17+ | 17+ |

## Features

- **Zero third-party runtime dependencies**: built-in lightweight JSON parser and HTTP client, no OkHttp/Jackson/Netty
- **Static utilities out of the box**: `OpenAiUtil.chat(model, prompt)` — one line to chat
- **Module-level isolation**: pull only the platform modules you need
- **Full Chinese platform coverage**: OpenAI / Azure / Anthropic / Gemini / DeepSeek / Qwen / Zhipu / Moonshot / Doubao / Baidu / Ollama / Grok / Mistral / Cohere / llama.cpp / AWS Bedrock; **v1.9.0 adds 7 OpenAI-compatible platforms**: MiniMax / StepFun / Baichuan / 01.AI (Lingyi) / SiliconFlow / Tencent Hunyuan / iFlytek Spark
- **Streaming**: unified SSE streaming interface with per-chunk callback
- **Function Calling**: tool declaration and invocation closed loop
- **Embedding**: vector generation (see table below for supported platforms)
- **Image Generation**: unified `ImageClient` abstraction supporting DALL·E / Wanx / CogView / ERNIE-ViLG / Gemini Imagen; async platforms handle polling internally, exposing a synchronous API
- **Video Generation**: unified `VideoClient` abstraction supporting Sora / Wan / CogVideoX / Seedance / Azure Sora 2; all platforms use async task polling internally, exposing a synchronous API
- **Speech TTS/STT**: unified `AudioClient` abstraction (TTS synthesis + STT transcription) supporting OpenAI / CosyVoice / GLM-TTS / Doubao / Baidu / Azure Speech; binary audio / URL / Base64 response formats
- **RAG**: end-to-end retrieval-augmented generation pipeline (`sure-ai-rag`) — document loaders (local file / URL), recursive / Markdown / fixed-size / semantic / parent-child splitters, vector stores (in-memory + 8 external adapters: Milvus/Chroma/Qdrant/Pinecone/Weaviate/Elasticsearch/OpenSearch/Redis), vector + BM25 keyword hybrid retrieval, prompt templates + query rewriting. **Advanced retrieval (v1.8.0)**: HyDE / Multi-Query (RRF) / CRAG corrective / parent-child small-to-big / semantic chunking / multimodal RAG; **GraphRAG** (entity-relation extraction → community detection → per-community summaries); **RAG evaluation** (faithfulness / context_precision / context_recall / answer_relevancy + threshold assertions + trace replay regression); **portable metadata filter** (FilterExpression abstraction + 6 dialect translators). See [docs/rag.md](docs/rag.md), [docs/vector-stores.md](docs/vector-stores.md), [docs/prompt-template.md](docs/prompt-template.md)
- **Agent orchestration (ReAct + Plan-and-Execute)**: `sure-ai-agent` — tool registry + Function Calling argument validation + ReAct orchestrator + PlanExecuteAgent (plan → step-by-step execution → synthesis, 3-level plan parsing fallback); multi-agent orchestration (TaskSplitter/AgentOrchestrator/ResultAggregator parallel execution + exception isolation); built-in tools (HttpTool/DateTimeTool/CalculatorTool whitelist arithmetic); conversation memory (ConversationMemory ring buffer, optional injection). Drivable by any `AiClient`, with exception / max-iteration / timeout guards. See [docs/agent.md](docs/agent.md)
- **Production-grade agents (v1.7.0)**: four optional production capabilities layered on ReActAgent — checkpoint persistence (`CheckpointStore`/`AgentCheckpointer` replay-based recovery, auto-save at run start / each iteration / end); streaming events (8 `AgentEvent` types + `StreamingAgentListener` bridge + SSE writer); HITL approval (`ApprovalPolicy` with 4 built-in strategies + `ApprovalHandler` with 4 built-in handlers, rejection/timeout fed back to the model); long-term memory (`LongTermMemory` recalls Top-K into system before run and auto-extracts after, vector/text dual-path fallback). Pass `null` to disable any of them; behavior matches historical versions when off. See [docs/agent-advanced.md](docs/agent-advanced.md)
- **MCP Client**: `sure-ai-mcp` — Model Context Protocol JSON-RPC 2.0 client with stdio (ProcessBuilder subprocess) / Streamable HTTP (JDK HttpClient + SSE aggregation) transports, initialize handshake + tools/resources/prompts capability APIs; **McpTool adapter** bulk-registers MCP server tools into `ToolRegistry`, composable with ReActAgent. See [docs/mcp.md](docs/mcp.md)
- **MCP Server**: `sure-ai-mcp-server` — exposes sureai's multi-platform `AiClient`/`EmbeddingClient`/`ImageClient` as a standard MCP Server: pure-JDK protocol engine + stdio/Streamable HTTP transports, a registry-based tool model that keeps the core free of platform deps, compatible with stateful 2025-06-18 and experimentally adapted to stateless 2026-07-28. See [docs/mcp-server.md](docs/mcp-server.md)
- **AI Gateway**: `sure-ai-gateway` — transparent multi-provider unified gateway implementing `AiClient`; 6 routing strategies (Explicit/Capability/RoundRobin/Weighted/LowestLatency/LowestCost, composable) + automatic failover (4xx no-failover / 5xx-timeout failover, unhealthy eviction with cooldown) + API key pool rotation (auto-switch on 401/429) + tenant quota & budget. See [docs/gateway.md](docs/gateway.md)
- **Cost Metering**: `sure-ai-core com.sure.ai.cost` — price catalog (18 built-in models) + per-call cost calculation (with cached tokens) + aggregation by tenant/model/time-window, thread-safe in-memory. See [docs/cost.md](docs/cost.md)
- **OpenAI-Compatible Proxy**: `sure-ai-proxy` — standalone HTTP service (LiteLLM proxy pattern, JDK HttpServer, zero new deps), /v1/chat/completions (non-stream + SSE stream), /v1/models, /v1/embeddings; virtual key auth; ProxyConfig via properties. See [docs/proxy.md](docs/proxy.md)
- **Observability (retry callbacks / metrics / rate limit)**: `RetryListener` retry event callbacks + `MetricsCollector` metrics (built-in zero-dependency `AiMetrics`) + client-side QPS rate limiting (sure-core token bucket), optional `sure-ai-micrometer` Micrometer/Prometheus bridge; zero overhead when not mounted. See [docs/observability.md](docs/observability.md)
- **Response Cache**: `ChatCacheKey` request-normalized SHA-256 + `CacheStore` SPI + built-in `LruCacheStore` (LRU+TTL, pure JDK), disabled by default with zero overhead; cache hits bypass network/metrics/retry; Redis adapter example in docs. See [docs/cache.md](docs/cache.md)
- **Spring Boot Starter**: `sure-ai-spring-boot-starter` auto-configuration (`sure.ai.<platform>.api-key` property binding + `@ConditionalOnProperty` conditional assembly + `@Autowired` injection), for Spring Boot projects only; core/platform modules have zero Spring dependency. See [docs/spring-boot.md](docs/spring-boot.md)
- **Quarkus extension (v1.9.0)**: `sure-ai-quarkus-extension` (runtime + deployment dual module) conditionally registers each `XxxClient` as an Arc synthetic `@Singleton` bean based on `sure.ai.<platform>.api-key`, ready for injection; configuration mirrors the Spring Starter, and core/platform modules have zero Quarkus dependency. See [docs/quarkus-extension.md](docs/quarkus-extension.md)
- **Full async / virtual threads (v1.9.0)**: `AiClient` gains a `chatAsync`/`chatStreamAsync` default method family; `AsyncClients` wraps any synchronous client into `AsyncAiClient`/`AsyncEmbeddingClient`/`AsyncImageClient`/`AsyncVideoClient`/`AsyncAudioClient` in one line, running on JDK 21 virtual threads (`AsyncExecutors.virtualThreadExecutor()`); cancelable `CompletableFuture` with exceptions propagated as-is. See [docs/async.md](docs/async.md)
- **OpenTelemetry GenAI bridge (v1.9.0)**: `sure-ai-otel` bridges `MetricsCollector`/`RetryListener`/`AgentEventSink` to OTel GenAI semantic-convention metrics (`gen_ai.client.operation.duration` / `input_tokens` / `output_tokens`); depends only on the OTel API (provided), SDK/exporters are user-supplied; passing a `null` MeterProvider degrades to a no-op with zero overhead. See [docs/observability.md](docs/observability.md#opentelemetry-genai-桥接可选模块)
- **Command-line tool (v2.0.0)**: `sure-ai-cli` — zero-dependency terminal chat: `chat` / `stream` / `rag` (local docs) / `list` / `repl` subcommands, switching across all 23 platforms from one binary; builds as a fat jar and compiles to a single-file GraalVM native executable. Use the whole library without writing a line of Java. See [docs/cli.md](docs/cli.md)
- **Maven project archetype (v2.0.0)**: `sure-ai-archetype` — one `mvn archetype:generate` produces a Hello World project with BOM import, a single-platform Client call, README and .gitignore; a platform property switches across the 23 platforms. See [docs/COOKBOOK.md](docs/COOKBOOK.md)
- **GraalVM native-image AOT hardening (v2.0.0)**: the whole library ships `META-INF/native-image/**` reflection / resource / compile-args metadata (42 reflect-config entries: core 30 + agent 8 + mcp-server 4); fat jars inline it automatically so native builds need no hand-written config; `NativeImageMetadataTest` guards the core model package against omissions. See [docs/native-image.md](docs/native-image.md)
- **Cookbook recipes (v2.0.0)**: [docs/COOKBOOK.md](docs/COOKBOOK.md) — 9 copy-paste-minimal runnable scenarios (single-platform chat / multi-platform gateway / RAG / Agent tool-calling / async virtual threads / observability / CLI / archetype / native) plus 3 offline-runnable sample apps (`ExamplesRunner` in `sure-ai-examples`).
- **Circuit Breaker**: `CircuitBreaker` 3-state machine (CLOSED→OPEN→HALF_OPEN), sliding-window failure count + open timeout + half-open probe, optional via `AiConfig.circuitBreaker`, disabled by default (zero overhead), nested with retry/rate-limit. See [docs/circuit-breaker.md](docs/circuit-breaker.md)
- **Rerank**: unified `RerankClient` abstraction, Qwen qwen3-rerank integration, pluggable two-stage re-ranking in the RAG retrieval chain
- **Structured Output**: unified `response_format` abstraction (json_object / JSON Schema), zero-dependency `JsonMapper` strong-typed record deserialization, adapted across 8 platforms
- **Multimodal Image Understanding**: `MessagePart` content-block architecture (text + image), normalized image input across OpenAI-compatible / Gemini / Anthropic / Baidu
- **PDF Document Input**: `DocumentPart` content block, PDF document understanding adapted across 5 platforms
- **Prompt Caching**: Anthropic `cache_control` / Gemini `cachedContent` / OpenAI automatic caching, cutting repeated-prefix cost for long contexts
- **Batches**: unified `BatchClient` abstraction, async batch inference across OpenAI / Azure / Zhipu / Anthropic, built-in polling
- **Realtime Voice**: unified `RealtimeClient` full-duplex WebSocket abstraction across 5 platforms (OpenAI / Gemini / Qwen / Zhipu / Doubao), injectable connector for easy mocking
- **Reasoning / Thinking**: unified `reasoningEffort` / `thinkingConfig` abstraction with `reasoningContent` chain-of-thought parsing, adapted across 5 platforms (OpenAI / Azure / Gemini / Anthropic / Qwen)
- **Grounding / Web Search**: unified `grounding` toggle with both tool-style (web_search / googleSearch) and boolean-style (enable_search) paradigms, 6 platforms (OpenAI / Azure / Gemini / Qwen / Zhipu / Doubao), source citation parsing
- **Fine-tuning**: unified `FineTuneClient` abstraction (upload training file + create/query jobs), 3 platforms (OpenAI / Azure / Baidu Qianfan)
- **Moderation**: unified `ModerationClient` abstraction with normalized categories and scores, OpenAI / Azure
- **Model Listing**: unified `ModelsClient` abstraction across 5 platforms (OpenAI / Azure / Gemini / Anthropic / Qwen)
- **Capability guard (v1.4.0)**: each platform Client explicitly declares the set of capabilities it supports; calling an unsupported capability (e.g. `embed()` on DeepSeek) throws `AiException: deepseek does not support EMBED capability` immediately before any request is sent, instead of firing the request and receiving a vague upstream 4xx. Per-platform capability matrix see [docs/capabilities.md](docs/capabilities.md)
- **Environment variable auto-config**: lazy-loads from `SURE_AI_*` env vars when not explicitly initialized
- **JDK 21**: records, pattern matching, switch patterns

## Modules & Platforms

| Platform | artifactId | Default baseUrl | Auth | Streaming | Embedding | Image Gen | Video Gen | TTS | STT | Function Calling | Rerank | Structured | Multimodal | PDF | Cache | Batches | Realtime | Reasoning | Grounding | Fine-tune | Moderation | Models |
|----------|-----------|-----------------|------|-----------|-----------|-----------|-----------|-----|-----|-----------------|--------|-----------|------------|-----|-------|---------|----------|-----------|-----------|-----------|------------|--------|
| OpenAI | `sure-ai-openai` | `https://api.openai.com/v1` | Bearer | ✅ | ✅ | ✅ DALL·E 3 | ✅ Sora 2 | ✅ tts-1 | ✅ whisper-1 | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ auto | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Azure OpenAI | `sure-ai-azure` | `https://{resource}.openai.azure.com` | api-key header | ✅ | ✅ | ✅ DALL·E 3 | ✅ Sora 2 | ✅ Speech | ✅ Speech | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ auto | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Anthropic | `sure-ai-anthropic` | `https://api.anthropic.com/v1` | x-api-key header | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ tool_use | ✅ | ✅ | ✅ cache_control | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |
| Google Gemini | `sure-ai-gemini` | `https://generativelanguage.googleapis.com/v1beta` | ?key= query param | ✅ | ✅ | ✅ Imagen | ❌ Veo(OAuth) | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ cachedContent | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| DeepSeek | `sure-ai-deepseek` | `https://api.deepseek.com` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Qwen | `sure-ai-qwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Bearer | ✅ | ✅ | ✅ Wanx (async) | ✅ Wan 2.6 (async) | ✅ CosyVoice | ✅ Qwen-ASR | ✅ | ✅ qwen3-rerank | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ console | ❌ | ✅ |
| Zhipu GLM | `sure-ai-zhipu` | `https://open.bigmodel.cn/api/paas/v4` | JWT (HS256) | ✅ | ✅ | ✅ CogView | ✅ CogVideoX (async) | ✅ GLM-TTS | ✅ GLM-ASR | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ console | ❌ | ❌ |
| Moonshot | `sure-ai-moonshot` | `https://api.moonshot.cn/v1` | Bearer | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Doubao | `sure-ai-doubao` | `https://ark.cn-beijing.volces.com/api/v3` | Bearer | ✅ | ✅ | ❌ | ✅ Seedance (async) | ✅ seed-tts-2.0 | ✅ BigASR | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ console | ❌ | ❌ |
| Baidu Qianfan | `sure-ai-baidu` | `https://aip.baidubce.com` | access_token (auto-cached) | ✅ | ✅ | ✅ ERNIE-ViLG (async) | ❌ | ✅ DuXiaomei | ✅ Short ASR | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| Ollama | `sure-ai-ollama` | `http://localhost:11434` | None (local) | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Grok (xAI) | `sure-ai-grok` | `https://api.x.ai/v1` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |
| Mistral | `sure-ai-mistral` | `https://api.mistral.ai/v1` | Bearer | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Cohere | `sure-ai-cohere` | `https://api.cohere.com/v2` | Bearer | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ rerank-v3.5 | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| llama.cpp | `sure-ai-llamacpp` | `http://localhost:8080/v1` | Optional Bearer | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| AWS Bedrock | `sure-ai-bedrock` | `https://bedrock-runtime.{region}.amazonaws.com` | SigV4 (AK/SK) | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| MiniMax | `sure-ai-minimax` | `https://api.minimax.cn/v1` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| StepFun | `sure-ai-stepfun` | `https://api.stepfun.com/v1` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Baichuan | `sure-ai-baichuan` | `https://api.baichuan-ai.com/v1` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 01.AI Lingyi | `sure-ai-lingyi` | `https://api.lingyiwanwu.com/v1` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| SiliconFlow | `sure-ai-siliconflow` | `https://api.siliconflow.cn/v1` | Bearer | ✅ | ✅ BAAI/bge-m3 | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Tencent Hunyuan | `sure-ai-hunyuan` | `https://api.hunyuan.cloud.tencent.com/v1` | Bearer | ✅ | ✅ hunyuan-embedding | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| iFlytek Spark | `sure-ai-spark` | `https://spark-api-open.xf-yun.com/v1` | Bearer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

Per-platform docs: [AWS Bedrock](docs/bedrock.md) · [Cohere](docs/cohere.md) · [Grok](docs/grok.md) · [Mistral](docs/mistral.md) · [llama.cpp](docs/llamacpp.md); per-platform docs for the rest (OpenAI / Azure / Anthropic / Gemini / DeepSeek / Qwen / Zhipu / Moonshot / Doubao / Baidu / Ollama and the v1.9.0 additions MiniMax / StepFun / Baichuan / Lingyi / SiliconFlow / Hunyuan / Spark) live under `docs/platforms/`; per-platform capability matrix see [docs/capabilities.md](docs/capabilities.md).

Aggregation modules: `sure-ai-all` (one dependency for all platforms), `sure-ai-bom` (version management).

Application layer: `sure-ai-cli` (command-line Q&A: chat / stream / rag / list / repl, one-key switch across all 23 platforms; buildable as a fat jar and a [GraalVM native-image](docs/native-image.md) executable; not part of the `sure-ai-all` aggregate chain, see [docs/cli.md](docs/cli.md)).

Project scaffold: `sure-ai-archetype` (`mvn archetype:generate` one-shot Hello World Java project: pom imports BOM + single-platform Client call + README + .gitignore; not part of the `sure-ai-all` / `sure-ai-bom` aggregate chains).

## Quick Start (5 minutes)

**1. Import the BOM for unified versions, then pick one platform module** (OpenAI here; coordinates for the other 22 platforms see the [Modules & Platforms](#modules--platforms) table below):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.tasure</groupId>
      <artifactId>sure-ai-bom</artifactId>
      <version>1.4.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
  </dependency>
</dependencies>
```

> Want all 23 platforms + RAG + Agent at once? Replace the platform dependency above with the aggregator `sure-ai-all` (`type=pom`).

**2. One-line call** (first `export SURE_AI_OPENAI_API_KEY=sk-xxx`):

```java
import com.sure.ai.openai.OpenAiUtil;

String reply = OpenAiUtil.chat("gpt-4o-mini", "Hello!").firstText();
System.out.println(reply);
```

**3. Run it**: with JDK 21+, run this main method in your IDE and the model's reply prints to the console.

More copy-paste recipes (streaming / multi-platform gateway / RAG / Agent tool-calling / async virtual threads / observability / CLI / archetype / native build) see [docs/COOKBOOK.md](docs/COOKBOOK.md).

### Image Generation

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.ImageRequest;

// Synchronous return (async platforms like Wanx/ERNIE-ViLG poll internally)
String imageUrl = OpenAiUtil.image(OpenAiModels.DALL_E_3, "a cute kitten").firstUrl();

// Or use Builder for size/quality/count
ImageRequest req = ImageRequest.builder()
    .model(OpenAiModels.DALL_E_3)
    .prompt("cyberpunk city skyline at night")
    .size("1024x1024")
    .quality("hd")
    .build();
String url = OpenAiUtil.image(req).firstUrl();
```

See [docs/images.md](docs/images.md) for platform configuration and async polling details.

### Video Generation

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.VideoRequest;

// All platforms are async; SDK polls internally, returns synchronously
String videoUrl = OpenAiUtil.video(OpenAiModels.SORA_2, "a cat running on grass").firstUrl();

// Or use Builder for duration/resolution/first-last-frame
VideoRequest req = VideoRequest.builder()
    .model(OpenAiModels.SORA_2)
    .prompt("cyberpunk city skyline at night, slow motion")
    .duration(8)
    .size("1280x720")
    .build();
String url = OpenAiUtil.video(req).firstUrl();
```

See [docs/video.md](docs/video.md) for platform configuration and async polling details.

### Speech TTS / STT

```java
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.SttResponse;

// TTS: text → binary audio
TtsResponse tts = OpenAiUtil.tts(OpenAiModels.TTS_1, "Hello, world", "alloy");
byte[] audio = tts.audio();  // save as mp3 directly

// STT: audio → transcribed text
SttResponse stt = OpenAiUtil.stt(OpenAiModels.WHISPER_1, audio);
System.out.println(stt.text());
```

See [docs/audio.md](docs/audio.md) for platform configuration and response format details.

### Structured Output

```java
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.util.JsonMapper;

// Target record: field names must match the JSON keys the model emits
record CityInfo(String city, String country, int population) {}

String text = OpenAiUtil.chat(ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("Extract city info as JSON only: city/country/population. Text: Tokyo is the capital of Japan.")))
    .responseFormat("json_object")
    .build())
    .firstText();

// Zero-dependency strong-typed deserialization, no manual string parsing
CityInfo info = JsonMapper.fromJson(Json.parse(text).getAsJsonObject(), CityInfo.class);
```

Per-platform `response_format` differences (Anthropic simulated via forced tool_use,
Gemini `responseSchema`, Baidu string values) see [docs/structured-output.md](docs/structured-output.md).

### Multimodal Image Understanding

```java
import com.sure.ai.model.*;

// One user message = text part + image part (URL or Base64)
String desc = OpenAiUtil.chat(ChatRequest.builder()
    .model("gpt-4o")
    .messages(List.of(ChatMessage.user(List.of(
        TextPart.of("Describe this image in one sentence."),
        ImagePart.ofUrl("https://example.com/cat.jpg")
    ))))
    .build())
    .firstText();
```

Content-block architecture, PDF input and prompt caching see [docs/multimodal.md](docs/multimodal.md);
Rerank see [docs/rerank.md](docs/rerank.md); Batches see [docs/batches.md](docs/batches.md).

### Moderation

```java
import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;
import com.sure.ai.openai.OpenAiUtil;

ModerationResponse resp = OpenAiUtil.client()
    .moderate(ModerationRequest.of("text to moderate"));

System.out.println("flagged=" + resp.flagged());   // true if any result is flagged
resp.results().forEach(r -> r.categoryScores()
    .forEach((k, v) -> System.out.printf("%s=%.4f%n", k, v)));
```

See [docs/moderation.md](docs/moderation.md).

### Model Listing

```java
import com.sure.ai.model.Model;
import com.sure.ai.openai.OpenAiUtil;

for (Model m : OpenAiUtil.client().listModels()) {
    System.out.println(m.id() + "  owned_by=" + m.ownedBy());
}
```

See [docs/models.md](docs/models.md); Realtime see [docs/realtime.md](docs/realtime.md),
Reasoning see [docs/thinking.md](docs/thinking.md), Grounding see [docs/grounding.md](docs/grounding.md),
Fine-tuning see [docs/fine-tuning.md](docs/fine-tuning.md).

## Maven Dependencies

Pull individual platform modules:

```xml
<!-- OpenAI -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Azure OpenAI -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-azure</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Anthropic Claude -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-anthropic</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Google Gemini -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-gemini</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- DeepSeek -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-deepseek</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Qwen DashScope -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-qwen</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Zhipu GLM -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-zhipu</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Moonshot Kimi -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-moonshot</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Doubao Volcano Engine -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-doubao</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Baidu Qianfan -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-baidu</artifactId>
    <version>1.4.0</version>
</dependency>

<!-- Ollama Local -->
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-ollama</artifactId>
    <version>1.4.0</version>
</dependency>
```

Pull all platforms at once:

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-all</artifactId>
    <version>1.4.0</version>
    <type>pom</type>
</dependency>
```

## Streaming

```java
OpenAiUtil.chatStream(
    ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("Introduce Java in one sentence.")))
        .build(),
    chunk -> {
        if (chunk.deltaText() != null) {
            System.out.print(chunk.deltaText());
        }
    }
);
```

## Function Calling

```java
ToolFunction weatherFn = ToolFunction.of(
    "getWeather",
    "Get current weather for a city",
    "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"
);

ChatRequest req = ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("How's the weather in Shanghai?")))
    .tools(List.of(ToolSpec.of(weatherFn)))
    .build();

ChatResponse resp = OpenAiUtil.chat(req);
List<ToolCall> calls = resp.choices().get(0).message().toolCalls();
// Execute the tool, then append result as ChatMessage.tool(toolCallId, result) and re-request
```

## Embedding

```java
EmbeddingResponse resp = OpenAiUtil.embed("text-embedding-3-small", "hello world");
float[] vector = resp.embeddings().get(0);
System.out.println("Vector dimensions: " + vector.length);
```

## RAG (Retrieval-Augmented Generation)

`sure-ai-rag` provides an end-to-end RAG pipeline (text splitting → embedding → similarity
retrieval → augmented generation), decoupled from any platform. Combine any platform client
that supports chat + embeddings:

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-rag</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
OpenAiClient client = OpenAiClient.builder().apiKey("sk-xxx").build();

RagPipeline pipeline = RagUtil.pipeline(client, client,
        OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL);

pipeline.ingest("sureai-intro", "sureai is a zero-dependency Java LLM toolkit ...");
ChatResponse answer = pipeline.ask("What capabilities does sureai support?");
```

An in-memory vector store (cosine similarity) is included out of the box, along with 8 external
adapters: Milvus / Chroma / Qdrant / Pinecone / Weaviate / Elasticsearch / OpenSearch / Redis
(9 implementations total); implement the `VectorStore` interface to plug in FAISS / pgvector.
Built-in document loaders (local file / URL), BM25 keyword retrieval and weighted
vector+keyword hybrid retrieval, Markdown / fixed-size / semantic / parent-child splitters,
plus advanced capabilities: HyDE / Multi-Query / CRAG / GraphRAG / RAG evaluation and a
portable metadata filter abstraction. See [docs/rag.md](docs/rag.md),
[docs/vector-stores.md](docs/vector-stores.md).

## Environment Variables

| Platform | Variable | Required | Description |
|----------|----------|----------|-------------|
| OpenAI | `SURE_AI_OPENAI_API_KEY` | ✅ | API Key |
| | `SURE_AI_OPENAI_BASE_URL` | ❌ | Override default baseUrl |
| Azure | `SURE_AI_AZURE_API_KEY` | ✅ | API Key |
| | `SURE_AI_AZURE_RESOURCE` | ❌ | Azure resource name |
| | `SURE_AI_AZURE_BASE_URL` | ❌ | Full baseUrl |
| Anthropic | `SURE_AI_ANTHROPIC_API_KEY` | ✅ | API Key |
| | `SURE_AI_ANTHROPIC_BASE_URL` | ❌ | Override default baseUrl |
| Gemini | `SURE_AI_GEMINI_API_KEY` | ✅ | API Key |
| | `SURE_AI_GEMINI_BASE_URL` | ❌ | Override default baseUrl |
| DeepSeek | `SURE_AI_DEEPSEEK_API_KEY` | ✅ | API Key |
| | `SURE_AI_DEEPSEEK_BASE_URL` | ❌ | Override default baseUrl |
| Qwen | `SURE_AI_QWEN_API_KEY` | ✅ | DashScope API Key |
| | `SURE_AI_QWEN_BASE_URL` | ❌ | Override default baseUrl |
| Zhipu | `SURE_AI_ZHIPU_API_KEY` | ✅ | `id.secret` format |
| | `SURE_AI_ZHIPU_BASE_URL` | ❌ | Override default baseUrl |
| Moonshot | `SURE_AI_MOONSHOT_API_KEY` | ✅ | API Key |
| | `SURE_AI_MOONSHOT_BASE_URL` | ❌ | Override default baseUrl |
| Doubao | `SURE_AI_DOUBAO_API_KEY` | ✅ | Ark API Key |
| | `SURE_AI_DOUBAO_BASE_URL` | ❌ | Override default baseUrl |
| Baidu | `SURE_AI_BAIDU_API_KEY` | ✅ | Qianfan API Key |
| | `SURE_AI_BAIDU_SECRET_KEY` | ✅ | Qianfan Secret Key |
| | `SURE_AI_BAIDU_BASE_URL` | ❌ | Override default baseUrl |
| Ollama | `SURE_AI_OLLAMA_BASE_URL` | ❌ | Override `localhost:11434` |
| MiniMax | `SURE_AI_MINIMAX_API_KEY` | ✅ | API Key |
| | `SURE_AI_MINIMAX_BASE_URL` | ❌ | Override default `api.minimax.cn/v1` (intl `api.minimax.io/v1`) |
| StepFun | `SURE_AI_STEPFUN_API_KEY` | ✅ | API Key |
| | `SURE_AI_STEPFUN_BASE_URL` | ❌ | Override default (intl `api.stepfun.ai/v1`) |
| Baichuan | `SURE_AI_BAICHUAN_API_KEY` | ✅ | API Key |
| | `SURE_AI_BAICHUAN_BASE_URL` | ❌ | Override default baseUrl |
| 01.AI Lingyi | `SURE_AI_LINGYI_API_KEY` | ✅ | API Key |
| | `SURE_AI_LINGYI_BASE_URL` | ❌ | Override default baseUrl |
| SiliconFlow | `SURE_AI_SILICONFLOW_API_KEY` | ✅ | API Key |
| | `SURE_AI_SILICONFLOW_BASE_URL` | ❌ | Override default (intl `api.siliconflow.com/v1`) |
| Hunyuan | `SURE_AI_HUNYUAN_API_KEY` | ✅ | API Key |
| | `SURE_AI_HUNYUAN_BASE_URL` | ❌ | Override default (can migrate to TokenHub endpoint) |
| Spark | `SURE_AI_SPARK_API_KEY` | ✅ | Console `APIPath:APIKey` passed whole as Bearer |
| | `SURE_AI_SPARK_BASE_URL` | ❌ | Override default baseUrl |

## Architecture & Isolation

sureai enforces strict module-level isolation:

```
sure-ai-core          ← common models/interfaces/HTTP/JSON (depended on by all platforms)
sure-ai-rag           ← RAG pipeline (depends on core; platform-agnostic)
sure-ai-agent         ← Agent ReAct multi-tool loop (depends on core; platform-agnostic)
sure-ai-micrometer    ← observability Micrometer bridge (depends on core; micrometer-core is provided, not transitive)
sure-ai-otel          ← observability OpenTelemetry GenAI bridge (depends on core; otel-api is provided, not transitive)
sure-ai-quarkus-extension(-deployment) ← Quarkus auto-assembly extension (dual module; core/platforms have zero Quarkus deps)
  ├── sure-ai-openai
  ├── sure-ai-azure
  ├── sure-ai-anthropic
  ├── sure-ai-gemini
  ├── sure-ai-deepseek
  ├── sure-ai-qwen
  ├── sure-ai-zhipu
  ├── sure-ai-moonshot
  ├── sure-ai-doubao
  ├── sure-ai-baidu
  └── sure-ai-ollama
sure-ai-bom           ← version BOM
sure-ai-all           ← aggregate all platforms + RAG + Agent
sure-ai-examples      ← usage examples
sure-ai-cli           ← command-line Q&A (application layer, depends on sure-ai-all, outside the aggregate library chain)
sure-ai-archetype     ← mvn archetype:generate one-shot Hello World project (scaffold, outside the aggregate library chain)
```

**Core design principles:**

- Each platform module and the RAG module depend only on `sure-ai-core`; **zero cross-module dependencies**
- Core module ships with a built-in JSON parser and SSE line reader — no third-party libraries
- Static utilities use double-checked locking lazy init; auto-loads from env vars when not explicitly initialized
- Platform-specific auth logic (JWT / access_token cache / custom headers) is encapsulated within each module
- The RAG pipeline is platform-agnostic: any platform's chat + embedding clients can be combined

## Build

```bash
# Requires JDK 21+ and Maven 3.9+
mvn -B clean verify
```

This runs: compile → unit tests → checkstyle → spotbugs → jacoco coverage gate → license header check.

## Contributing

Issues and PRs are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md).

## Development & Governance Team

Future iterations are run by the **Software R&D Team**: the team lead orchestrates scope & acceptance, the product manager defines requirements & acceptance criteria, the software architect designs modules & APIs, the engineer implements, and a full-stack code reviewer independently audits (P0/P1/P2 triage). See [docs/TEAM.md](docs/TEAM.md); machine-readable config lives in [`.team/`](.team/).

## License

[Apache License 2.0](LICENSE) © sureai contributors
