# sureai Cookbook

A copy-paste cookbook for developers: every scenario gives a minimal, compilable code snippet, key points, and next steps. All snippets are kept in sync with the current source API; runnable examples live in the `sure-ai-examples` module (run them with `ExamplesRunner`).

> Convention: keys are read from environment variables with the uniform prefix `SURE_AI_<platform>_API_KEY`, e.g. `SURE_AI_OPENAI_API_KEY`.
> Examples for platforms without a configured key print a one-line notice and exit; no network request is made.

---

## 5-minute Quick Start

**Goal**: add the dependency, send your first chat, get it running.

**1. Import the BOM for unified versions, then pick platform modules**

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.tasure</groupId>
      <artifactId>sure-ai-bom</artifactId>
      <version>2.0.0-SNAPSHOT</version>
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

> To pull in all 23 platforms + RAG + Agent at once, replace the platform dependency above with the aggregator module `sure-ai-all` (`type=pom`).

**2. Minimal chat code**

```java
import com.sure.ai.openai.OpenAiUtil;

// Prerequisite: export SURE_AI_OPENAI_API_KEY=sk-xxx
String reply = OpenAiUtil.chat("gpt-4o-mini", "用一句话介绍你自己。").firstText();
System.out.println(reply);
```

**Key points**:
- The static helper `XxxUtil.chat(model, prompt)` returns in one line; keys are read from environment variables by default.
- The result is a `ChatResponse`; use `firstText()` for the first text segment.

**Next steps**: switch to a configured Client (timeouts, rate limiting, retries) in [Scenario 1](#scenario-1-single-platform-chat-streaming) and [docs/observability.md](observability.md).

---

## Scenario 1: Single-platform chat (incl. streaming)

**Goal**: sync and streaming chat with a Client.

```java
import java.util.List;
import com.sure.ai.client.AiConfig;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;

OpenAiClient client = new OpenAiClient(
        AiConfig.builder().apiKey(System.getenv("SURE_AI_OPENAI_API_KEY")).build());

// Sync
ChatResponse resp = client.chat(OpenAiModels.GPT_4O_MINI, "你好");
System.out.println(resp.firstText());

// Streaming: print each delta
client.chatStream(ChatRequest.builder()
        .model(OpenAiModels.GPT_4O_MINI)
        .messages(List.of(ChatMessage.user("写一首四行短诗。")))
        .build(),
        chunk -> {
            if (chunk.deltaText() != null) {
                System.out.print(chunk.deltaText());
            }
        });

client.close();
```

**Key points**:
- `ChatRequest.builder()` chains `model` / `messages` / `temperature` etc.; `ChatMessage.user/system/assistant` build messages.
- In the streaming callback only append `chunk.deltaText()`; a non-null `finishReason` on the final chunk marks the end of the stream.

**Next steps**: structured output in [docs/structured-output.md](structured-output.md); thinking mode in [docs/thinking.md](thinking.md).

---

## Scenario 2: Multi-platform switching with a unified gateway (routing)

**Goal**: route one request transparently across several platforms with round-robin, explicit targeting, and failover.

```java
import java.util.List;
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.CapabilityRoutingStrategy;
import com.sure.ai.gateway.ExplicitRoutingStrategy;
import com.sure.ai.gateway.FailoverConfig;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.gateway.RoundRobinStrategy;
import com.sure.ai.gateway.RoutingStrategy;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

// 1. Register several platform clients (openAiClient / deepSeekClient built by each platform module)
ClientRegistry registry = new ClientRegistry();
registry.register("openai", openAiClient);
registry.register("deepseek", deepSeekClient);

// 2. Routing chain: explicit platform -> capability filter -> round-robin; failover up to 3 attempts
RoutingStrategy strategy = new ExplicitRoutingStrategy(
        new CapabilityRoutingStrategy(new RoundRobinStrategy()));
FailoverConfig failover = FailoverConfig.builder().maxAttempts(3).build();
GatewayClient gateway = new GatewayClient(registry, strategy, failover);

// 3. Call it like any AiClient (extra[platform] forces a specific platform)
ChatResponse resp = gateway.chat(ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("你好")))
        .extra("platform", "openai")
        .build());
```

**Key points**:
- `GatewayClient` itself implements `AiClient`; callers never see how many providers sit behind it.
- Routing strategies compose freely: `ExplicitRoutingStrategy` / `CapabilityRoutingStrategy` / `RoundRobinStrategy` / `LowestCostStrategy` / `LowestLatencyStrategy` / `WeightedRoutingStrategy`.
- 4xx (auth/rate-limit) never fail over across providers; only 5xx/timeout/IO errors do, avoiding "reproduces everywhere".

**Next steps**: tenant quotas and key-pool rotation in [docs/gateway.md](gateway.md); a fully runnable three-platform comparison lives in `sure-ai-examples` (`MultiPlatformGatewayDemo`).

---

## Scenario 3: RAG knowledge-base Q&A (ingest + retrieve + generate)

**Goal**: split local documents, embed them into a store, then answer from retrieved context.

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.pipeline.RagPipeline;

OpenAiClient client = new OpenAiClient(
        AiConfig.builder().apiKey(System.getenv("SURE_AI_OPENAI_API_KEY")).build());

// Chat and embedding reuse the same client (OpenAI implements both AiClient and EmbeddingClient)
RagPipeline pipeline = RagUtil.pipeline(client, client,
        OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL);

// Ingest: auto-split -> embed -> write into the in-process vector store
int chunks = pipeline.ingest("sureai-intro",
        "sureai 是零第三方依赖的 Java 大模型工具库，支持流式对话、Function Calling 与 RAG。");

// Retrieve + generate: recall topK context into the system prompt automatically
var answer = pipeline.ask("sureai 支持哪些能力？", 3);
System.out.println(answer.firstText());
System.out.println("已入库分块数：" + chunks + "，向量库规模：" + pipeline.vectorStore().size());

client.close();
```

**Key points**:
- `RagUtil.pipeline(chatClient, embeddingClient, chatModel, embeddingModel)` wires everything in one line; defaults are `InMemoryVectorStore` + recursive character splitting.
- Custom splitter/store: replace with `RagPipeline.builder().splitter(new FixedSizeTextSplitter(120, 20)).vectorStore(...)`.
- Any platform's "chat client + embedding client" can be combined freely; the RAG pipeline is decoupled from providers.

**Next steps**: external vector stores (Milvus/Qdrant/PgVector etc.), hybrid retrieval, rerank in [docs/rag.md](rag.md) and [docs/vector-stores.md](vector-stores.md); a fully offline runnable example that reads local files is `KnowledgeBaseDemo`.

---

## Scenario 4: Agent tool calling (Function Calling)

**Goal**: register custom tools, let the model decide to call them, feed results back, and get the final answer.

```java
import java.util.List;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.agent.tool.builtin.CalculatorTool;
import com.sure.ai.agent.tool.builtin.DateTimeTool;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiClient;

ToolRegistry registry = new ToolRegistry();
registry.register(CalculatorTool.toToolFunction(), new CalculatorTool());
registry.register(DateTimeTool.toToolFunction(), new DateTimeTool());

ChatRequest base = ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.system("你是一个会用工具的助手。")))
        .build();

ReActAgent agent = new ReActAgent(client, base, registry, listener,
        5, java.time.Duration.ofSeconds(30));
String answer = agent.run("帮我算 (12+30)*2，并告诉我现在时间。");
System.out.println(answer);
```

**Key points**:
- `ToolRegistry.register(ToolFunction.of(name, desc, jsonSchema), handler)`; `handler` is a `ToolHandler` taking a `JsonObject` and returning plain text.
- `ReActAgent` runs the Thought→Action→Observation loop: on `tool_calls` it executes them and feeds the `tool` message back, until a plain-text answer arrives.
- Built-ins: `CalculatorTool` (whitelisted arithmetic, no eval/reflection), `DateTimeTool`, `HttpTool` (SSRF-protected).

**Next steps**: HITL approval, conversation/long-term memory, checkpoint persistence, multi-agent orchestration in [docs/agent.md](agent.md) and [docs/agent-advanced.md](agent-advanced.md); a streaming + offline-scripted example is `CodeAssistantDemo`.

---

## Scenario 5: Async / virtual threads (v1.9.0 AsyncClients)

**Goal**: run blocking calls on JDK virtual threads (JDK 21+ API, baseline 25) to hit several platforms concurrently.

```java
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatMessage;

// Any platform client ships default async methods running on a shared virtual-thread executor
var f1 = openAiClient.chatAsync(ChatRequest.builder()
        .model("gpt-4o-mini").messages(List.of(ChatMessage.user("介绍 RAG"))).build());
var f2 = deepSeekClient.chatAsync(ChatRequest.builder()
        .model("deepseek-chat").messages(List.of(ChatMessage.user("介绍 RAG"))).build());

// Wait for both concurrently
var both = f1.thenCombine(f2, (a, b) -> "openai=" + a.firstText() + " | deepseek=" + b.firstText());
System.out.println(both.get());
```

**Key points**:
- `chatAsync(ChatRequest)` returns `CompletableFuture<ChatResponse>`; `chatStreamAsync(req, consumer)` completes with `null` at stream end.
- Tasks run on daemon virtual threads named `sureai-vt-`; blocking IO yields the carrier thread automatically; exceptions land in the future's exceptional completion.
- Existing sync APIs are untouched; async arrives as zero-cost default methods.

**Next steps**: custom executors / controlled lifecycle via `AsyncAiClient` in [docs/async.md](async.md).

---

## Scenario 6: Observability (OTel bridge + metrics)

**Goal**: attach built-in metrics, or bridge to an OpenTelemetry GenAI semantic-convention backend.

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.observability.AiMetrics;

AiMetrics metrics = new AiMetrics();
AiConfig config = AiConfig.builder()
        .apiKey(System.getenv("SURE_AI_OPENAI_API_KEY"))
        .maxRetries(2)
        .rateLimitQps(10)                 // 0=off
        .metricsCollector(metrics)        // mount the built-in zero-dependency metrics
        .retryListener((attempt, status, ex, backoffMs, path) ->
                System.out.printf("retry #%d on %s status=%d%n", attempt, path, status))
        .build();

// Read a snapshot after calls
var s = metrics.snapshot();
System.out.println("requests=" + s.totalRequests() + " failure=" + s.failureCount()
        + " avgMs=" + s.avgDurationMs());
```

Bridging to OpenTelemetry (optional module `sure-ai-otel`):

```java
import com.sure.ai.otel.OtelSupport;

AiConfig config = AiConfig.builder()
        .apiKey(key)
        .metricsCollector(OtelSupport.metricsCollector(mp, "openai", "chat"))
        .retryListener(OtelSupport.retryListener(mp, "openai", "chat"))
        .build();
```

**Key points**:
- Without metrics/retryListener configured, behavior is identical to before, zero overhead.
- Retryable statuses are `429/5xx` with exponential backoff (`Retry-After` wins); IO/interruptions are not retried.
- Micrometer/Prometheus bridging uses `MicrometerMetricsAdapter`.

**Next steps**: the full metrics mapping table and retry semantics in [docs/observability.md](observability.md).

---

## Scenario 7: Using the CLI (sureai chat / stream / rag)

**Goal**: without writing Java, chat, stream, and do local-document RAG right from the terminal.

```bash
# Build the fat jar
mvn -pl sure-ai-cli -am package -DskipTests

# One-shot Q&A (default openai)
export SURE_AI_OPENAI_API_KEY=sk-xxx
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar chat "用一句话解释 RAG"

# Streaming
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar stream "写一首短诗"

# Local-document RAG (no document network download)
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar rag "公司成立于哪年？" --doc ./company.txt

# Switch platform / interactive repl
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar --provider deepseek repl
```

**Key points**:
- Subcommands `chat` / `stream` / `rag` / `list` / `repl`; global options `--provider` / `--api-key` / `--model` / `--base-url`.
- RAG flow: read local document -> recursive split -> `InMemoryVectorStore` ingest -> topK=4 retrieval -> prompt with context -> generate.

**Next steps**: the environment-variable table and exit codes in [docs/cli.md](cli.md).

---

## Scenario 8: Scaffolding a project (archetype)

**Goal**: generate a Hello World project with BOM, a single-platform Client call, and README in one shot.

```bash
mvn archetype:generate \
  -DgroupId=com.example \
  -DartifactId=my-ai-app \
  -Dversion=1.0.0-SNAPSHOT \
  -DarchetypeGroupId=io.github.tasure \
  -DarchetypeArtifactId=sure-ai-archetype \
  -DarchetypeVersion=2.0.0-SNAPSHOT
```

**Key points**:
- Output: a pom (BOM import) + a runnable OpenAI main class + README + `.gitignore`.
- The archetype is an application-level scaffold; it is not part of the `sure-ai-all` / `sure-ai-bom` aggregator chain.

**Next steps**: to switch providers, replace `OpenAiClient` in the generated class with the target platform module — all platform APIs share the same style.

---

## Scenario 9: Native compilation (GraalVM)

**Goal**: compile the app into a standalone native executable with no JVM dependency.

```bash
# 1) Build the fat jar (keeps META-INF/native-image/** AOT metadata)
mvn -B -DskipTests package

# 2) Native compile
native-image \
  -jar sure-ai-examples/target/sure-ai-examples-2.0.0-SNAPSHOT.jar \
  -o sureai-demo
```

**Key points**:
- The whole library ships hardened AOT metadata (`JsonMapper` / `JsonSchemaGenerator` reflection points + `java.net.http` protocols); the fat jar inlines `native-image/**` automatically.
- Build flags: `-H:+AddAllCharsets --enable-url-protocols=https,http`.
- Only classpath resources loaded at reflection hot spots need extra `-H:IncludeResources`.

**Next steps**: the metadata manifest and troubleshooting in [docs/native-image.md](native-image.md).

---

## Scenario 10: GraphRAG global topic retrieval (knowledge graph)

**Goal**: beyond vector search, answer questions that need a global view and cross-document synthesis using an "entity–relation graph + community topic summaries". The source lives in `com.sure.ai.rag.graph`, no external dependency.

```java
import java.util.List;
import com.sure.ai.rag.graph.GraphRagIndexer;
import com.sure.ai.rag.graph.GraphRagRetriever;
import com.sure.ai.rag.graph.LlmCommunitySummarizer;
import com.sure.ai.rag.graph.LlmEntityRelationExtractor;
import com.sure.ai.rag.model.Document;

// Extractor: LLM parses text into "subject | relation | object" triples (fault-tolerant, skips dirty lines)
var extractor = LlmEntityRelationExtractor.builder()
        .chatClient(client).model("gpt-4o-mini").build();
// Community summarizer: condenses one community's entities/relations into a sentence (falls back to mechanical join)
var summarizer = LlmCommunitySummarizer.builder()
        .chatClient(client).model("gpt-4o-mini").build();

// Build the graph: extract relations -> label-propagation community detection -> summarize each community
GraphRagIndexer indexer = GraphRagIndexer.builder()
        .extractor(extractor)
        .summarizer(summarizer)   // detector defaults to LabelPropagationCommunityDetector
        .build();
indexer.ingest(List.of(
        Document.of("doc-1", "sureai 由 TASure 发起，核心模块是 sure-ai-core。"),
        Document.of("doc-2", "sure-ai-core 提供 JDK HttpClient 与 JSON 解析，被 23 个平台模块复用。")));

// Retrieve: literal query hits on entities -> locate communities -> return summaries by hit count desc
GraphRagRetriever retriever = GraphRagRetriever.builder().indexer(indexer).build();
for (Document d : retriever.retrieve("sure-ai-core 复用了哪些模块？", 3)) {
    System.out.println("社区#" + d.metadata().get("communityId") + " → " + d.text());
}
System.out.println("实体数=" + indexer.graph().entities().size()
        + "，社区数=" + indexer.communities().size());
```

**Key points**:
- `KnowledgeGraph` dedupes entity names via `normalizeName` (strip whitespace, lowercase), thread-safe.
- `GraphRagRetriever.retrieve` is deterministic (sorted by query-hit entity count); retrieval makes zero extra LLM calls; only graph building (extraction + summarization) consumes tokens.
- Complementary to vector search: vectors find "related fragments", GraphRAG finds "global cross-document topics"; merge both recall paths.

**Offline**: graph building/summarization needs an LLM (**API key required**); without a key the extractor/summarizer degrade via existing fault tolerance (no graph written, mechanical-join fallback); a Fake client can run the whole pipeline.

**Next steps**: graph internals in the GraphRAG section of [docs/rag.md](rag.md); community detection and fusion with vector retrieval in the related discussions of [docs/agent-advanced.md](agent-advanced.md).

---

## Scenario 11: Agent human-in-the-loop approval

**Goal**: pause before high-risk tools (web requests, writes) run, ask a human or policy, and continue only on approval.

```java
import java.time.Duration;
import java.util.List;
import com.sure.ai.agent.AgentListener;
import com.sure.ai.agent.approval.ApprovalGate;
import com.sure.ai.agent.approval.ConsoleApprovalHandler;
import com.sure.ai.agent.approval.ToolNameApprovalPolicy;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.agent.tool.builtin.HttpTool;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

ToolRegistry registry = new ToolRegistry();
registry.register(HttpTool.toToolFunction(), new HttpTool());

// Policy: only whitelisted tools need approval; Handler: console interaction reading y/n
ApprovalGate gate = new ApprovalGate(
        ToolNameApprovalPolicy.of("http_call"),
        new ConsoleApprovalHandler());

ChatRequest base = ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.system("你可以联网查资料。")))
        .build();

// Pass approvalGate in the longest constructor (second-to-last parameter), LongTermMemory = null
ReActAgent agent = new ReActAgent(client, base, registry, new AgentListener() {
}, 5, Duration.ofSeconds(30), null, null, null, gate, null);

String answer = agent.run("帮我查一下 example.com 的首页标题。");
System.out.println(answer);
```

**Key points**:
- Before each tool execution `gate.requiresApproval(toolName, args)` runs the policy first; on a hit `gate.request(ApprovalRequest.of(agentId, tool, args, desc, highRisk))` blocks until an `ApprovalDecision`.
- Swappable policies: `AllToolsApprovalPolicy` / `NeverApprovalPolicy` / `HighRiskApprovalPolicy` / `ToolNameApprovalPolicy.of(...)`; swappable handlers: `ConsoleApprovalHandler` / `AutoApprovalHandler` / `AutoRejectHandler` / `TimeoutApprovalHandler`, or implement `ApprovalHandler` yourself.
- After a rejection the Agent does not fail: "rejected" is fed back as an observation so it can try another approach or give up.

**Offline**: the approval gate is purely local; running the full loop still needs a chat client (**API key**, or a Fake client).

**Next steps**: checkpoint persistence (resume after approval), conversation memory in [docs/agent.md](agent.md) and [docs/agent-advanced.md](agent-advanced.md).

---

## Scenario 12: Gateway multi-provider routing (weight + capability + failover)

**Goal**: weighted split, capability filtering, automatic failover — invisible to callers.

```java
import java.util.List;
import java.util.Set;
import com.sure.ai.client.Capability;
import com.sure.ai.gateway.CapabilityRoutingStrategy;
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.FailoverConfig;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.gateway.WeightedRoutingStrategy;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

ClientRegistry registry = new ClientRegistry();
// Multiple instances of one platform can be registered: 2nd param instanceId; 5th capabilities; 6th weight
registry.register("openai", "primary", openAiClient,
        Set.of(Capability.CHAT, Capability.CHAT_STREAM), 2.0, "gpt-4o-mini");
registry.register("openai", "backup", openAiClient,
        Set.of(Capability.CHAT, Capability.CHAT_STREAM), 1.0, "gpt-4o-mini");
registry.register("deepseek", "default", deepSeekClient,
        Set.of(Capability.CHAT), 1.0, "deepseek-chat");

// Filter candidates by capability first, then weighted random (higher weight -> more likely)
GatewayClient gateway = new GatewayClient(registry,
        new CapabilityRoutingStrategy(new WeightedRoutingStrategy()),
        FailoverConfig.builder().maxAttempts(3).build());

ChatResponse resp = gateway.chat(ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("用一句话介绍你自己。")))
        .build());
```

**Key points**:
- Routing strategies nest arbitrarily: `CapabilityRoutingStrategy` (keep only candidates with the needed capability) wraps `WeightedRoutingStrategy` (probability by weight); also `RoundRobinStrategy` / `LowestCostStrategy` / `LowestLatencyStrategy` / `ExplicitRoutingStrategy`.
- Multiple instances of one platform are distinguished by `instanceId`; default weight is 1.0.
- 4xx (auth/rate-limit) never fails over; 5xx/timeout/IO errors do, up to `maxAttempts`.

**Offline**: routing/failover logic is local; real results still need platform clients (**API key**); offline, use Fake clients to observe split and failover.

**Next steps**: tenant quotas, key-pool rotation, latency tracking in [docs/gateway.md](gateway.md); an offline three-platform same-question comparison is `sure-ai-examples`'s `MultiPlatformGatewayDemo`.

---

## Scenario 13: OTel GenAI tracing (OtelSupport)

**Goal**: bridge request duration, token usage, retries, and Agent events into OpenTelemetry GenAI semantic-convention metrics and export to your own backend.

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.otel.OtelSupport;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.MeterProvider;

// Bring your own OTel SDK (SDK/exporters are not transitively shipped with sure-ai-otel)
OpenTelemetry otel = ...; // e.g. OtlpHttpExporter + BatchSpanProcessor
MeterProvider mp = otel.getMeterProvider();

AiConfig config = AiConfig.builder()
        .apiKey(System.getenv("SURE_AI_OPENAI_API_KEY"))
        .metricsCollector(OtelSupport.metricsCollector(mp, "openai", "chat"))
        .retryListener(OtelSupport.retryListener(mp, "openai", "chat"))
        .build();

// Optionally bridge the Agent event stream
var agentEventSink = OtelSupport.agentEventSink();
```

**Key points**:
- Mapped to `gen_ai.client.operation.duration` (DoubleHistogram), `input_tokens` / `output_tokens` (LongCounter), with common attributes `gen_ai.operation.name` / `gen_ai.provider.name` / `gen_ai.request.model`.
- Passing `MeterProvider.noop()` or null is all-no-op, zero overhead; without an OTel SDK at runtime this module is never loaded.
- `sure-ai-otel` depends only on the OTel API (provided); SDK/exporters are yours to assemble.

**Offline**: the metric bridge is locally testable (`MeterProvider.noop()` won't fail); real export needs an OTel collector (**as needed**).

**Next steps**: the full metrics mapping table and retry semantics in [docs/observability.md](observability.md); the built-in zero-dependency `AiMetrics` (no OTel) in Scenario 6.

---

## Scenario 14: CLI + RAG offline local Q&A

**Goal**: without writing Java, feed local documents into `sureai-cli rag` and get answers — no document download at all.

```bash
# Build the fat jar
mvn -pl sure-ai-cli -am package -DskipTests

# 1. Ingest local documents and ask (--doc repeatable, multi-document)
export SURE_AI_OPENAI_API_KEY=sk-xxx
java -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar rag \
  "公司成立于哪年？主营业务是什么？" \
  --doc ./company.txt --doc ./history.txt

# 2. Flow: read local UTF-8 text -> recursive split -> InMemoryVectorStore ingest -> topK=4 retrieval -> prompt -> generate
# 3. Switch platform: --provider deepseek / --model xxx
java -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar rag "..." --doc ./a.txt --provider deepseek
```

**Key points**:
- The `rag` subcommand only reads local plain text from `--doc`; **no document network download at all**; the store is the in-process `InMemoryVectorStore`, destroyed on exit.
- Credential priority: `--api-key` > `SURE_AI_<platform>_API_KEY`; exit codes 0 success / 1 input error / 2 platform error.
- Without a key, other subcommands (`list`) run offline; `rag`'s embedding and generation still need a usable chat/embedding client (**API key**).

**Offline**: ingest/split/retrieval run locally; only the final generation and embedding call models.

**Next steps**: environment-variable table, exit codes, all subcommands in [docs/cli.md](cli.md); pipeline internals in [docs/rag.md](rag.md).

---

## Scenario 15: Multimodal retrieval (text+image ingest & recall)

**Goal**: store "text + image parts" as one multimodal document; text is embedded for retrieval; injecting an `ImageEmbedder` also embeds images.

```java
import java.util.List;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.TextPart;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.strategy.MultimodalDocument;
import com.sure.ai.rag.strategy.MultimodalIngestor;
import com.sure.ai.rag.strategy.MultimodalRetriever;

// Text embedding adapter (wrap your EmbeddingClient; EmbeddingResponse.embeddings() is the vector list)
EmbeddingProvider textEmbed = text ->
        embeddingClient.embed("text-embedding-3-small", text).embeddings().get(0);

var ingestor = MultimodalIngestor.builder()
        .store(RagUtil.inMemoryStore())
        .textEmbeddingProvider(textEmbed)
        // Without an ImageEmbedder, images are only registered in mm_image_count metadata, no error
        .build();

MultimodalDocument doc = MultimodalDocument.of("card-1", List.of(
        TextPart.of("这是 sureai 架构图的说明：核心是 sure-ai-core。"),
        ImagePart.ofUrl("https://example.com/arch.png")));
ingestor.ingest(doc);

// Retrieve: plain-text Documents (Retriever interface), or full multimodal parts
var retriever = MultimodalRetriever.builder()
        .store(RagUtil.inMemoryStore())
        .textEmbeddingProvider(textEmbed)
        .ingestor(ingestor)
        .build();
for (MultimodalDocument md : retriever.retrieveMultimodal("架构核心是什么？", 3)) {
    System.out.println(md.text() + " 图片数=" + md.imageCount());
}
```

**Key points**:
- `MultimodalDocument.of(id, List<MessagePart>)` aggregates `TextPart` / `ImagePart`; `text()` joins all text for embedding, `images()` returns all image parts.
- `MultimodalIngestor` always stores text; images are embedded only when an `ImageEmbedder` (`embed(ImagePart)->float[]`) is injected, otherwise only `mm_image_count` metadata is written — can be enabled incrementally.
- `retrieve()` returns plain-text `Document` (usable as a regular Retriever in a RAG pipeline); `retrieveMultimodal()` returns full documents with image parts.

**Offline**: ingest/retrieval run locally; text embedding needs an embedding client (**API key**); image embedding needs your `ImageEmbedder`.

**Next steps**: multimodal document format and image understanding in [docs/multimodal.md](multimodal.md); vector stores and metadata filtering in [docs/vector-stores.md](vector-stores.md).

---

## Scenario 16: Native Langfuse observability (LangfuseExporters)

**Goal**: report every chat call to Langfuse (LLM observability platform) as trace + generation, zero-dependency — no OTel SDK or the official Langfuse SDK needed.

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.deepseek.DeepSeekClient;
import com.sure.ai.deepseek.DeepSeekModels;
import com.sure.ai.otel.langfuse.LangfuseExporters;

// Prerequisites:
//   export LANGFUSE_PUBLIC_KEY=pk-lf-...
//   export LANGFUSE_SECRET_KEY=sk-lf-...
//   export SURE_AI_DEEPSEEK_API_KEY=sk-xxx
// (optional LANGFUSE_HOST=self-hosted address, default https://cloud.langfuse.com)

// The one key line: build the exporter from env vars and attach it to the client
AiConfig config = AiConfig.builder()
        .apiKey(System.getenv("SURE_AI_DEEPSEEK_API_KEY"))
        .metricsCollector(LangfuseExporters.metricsCollectorFromEnv())
        .build();

DeepSeekClient client = new DeepSeekClient(config);
var resp = client.chat(DeepSeekModels.DEEPSEEK_CHAT, "用一句话回答：1+1等于几？");
System.out.println(resp.firstText());
client.close();
```

Explicit configuration (self-hosted instance or a named environment):

```java
import com.sure.ai.otel.langfuse.LangfuseConfig;
import com.sure.ai.otel.langfuse.LangfuseExporters;

AiConfig config = AiConfig.builder()
        .apiKey(key)
        .metricsCollector(LangfuseExporters.metricsCollector(
                LangfuseConfig.of("https://cloud.langfuse.com", "pk-lf-...", "sk-lf-...", "production")))
        .build();
```

**Key points**:
- `LangfuseExporters.metricsCollectorFromEnv()` / `metricsCollector(LangfuseConfig)` return a standard `MetricsCollector` to mount via `AiConfig.metricsCollector(...)`; one logical call = one `trace` + one `generation` (with duration, model, token usage); retries are appended as `event-create`.
- Auth is HTTP Basic `base64(pk:sk)` POSTed to `/api/public/ingestion`; official env names win, sureai-prefixed (`SURE_AI_LANGFUSE_*`) fall back.
- **Silent degradation**: if either `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` is missing the exporter is a no-op — no network, no impact on normal chat; send failures only log a warning, never throw.

**Offline**: the exporter is locally testable (missing keys = no-op, no error); real reporting needs a usable Langfuse project key.

**Next steps**: env table, event mapping, and auth in the "Langfuse native export (v2.4.0)" section of [docs/observability.md](observability.md); OTel GenAI bridge in Scenario 13; built-in zero-dependency `AiMetrics` in Scenario 6.

---

## Scenario 17: Declarative AiService + Advisor chain (v2.5.0)

**Goal**: treat an annotated Java interface as an AI service ("interface as service") and assemble the four-piece set "semantic cache → logging → tool loop → structured validation" onto one Advisor chain.

```java
import java.util.List;

import com.sure.ai.client.AiClient;
import com.sure.ai.framework.FrameworkUtil;
import com.sure.ai.framework.advisor.Advisor;
import com.sure.ai.framework.advisor.LoggingAdvisor;
import com.sure.ai.framework.advisor.SemanticCacheAdvisor;
import com.sure.ai.framework.advisor.ToolCallingAdvisor;
import com.sure.ai.framework.advisor.StructuredOutputValidationAdvisor;
import com.sure.ai.framework.annotation.AiService;
import com.sure.ai.framework.annotation.Tool;
import com.sure.ai.framework.annotation.UserMessage;
import com.sure.ai.framework.cache.SemanticCache;

// Structured output target record
record Product(String name, int stock) {}

@AiService(model = "gpt-4o-mini")
interface Catalog {

    @UserMessage("{q}")
    Product lookup(String q);

    @Tool(description = "查库存")
    default int stockOf(String item) {
        return queryStockDb(item);   // your real stock query
    }
}

// 1) Semantic cache: embedding-similarity threshold hit (embedder is any core EmbeddingClient)
SemanticCache cache = SemanticCache.builder()
        .embedder(embeddingClient)
        .threshold(0.85)                 // default 0.85, [0,1]
        .build();

// 2) Advisor chain order: cache short-circuit -> logging -> tool loop -> structured self-correction
List<Advisor> advisors = List.of(
        new SemanticCacheAdvisor(cache),
        new LoggingAdvisor(),
        new ToolCallingAdvisor(),                 // default maxIterations=5
        new StructuredOutputValidationAdvisor());  // default maxRetries=2

// 3) One-line assembly
Catalog ai = FrameworkUtil.builder(Catalog.class, client)
        .advisors(advisors)
        .build();

// 4) Call: the model may auto-call stockOf; invalid output self-corrects; repeated synonyms hit the cache
Product p = ai.lookup("查一下《深入理解计算机系统》的库存");
System.out.println(p.name() + " 库存=" + p.stock());
```

A minimal version (no Advisors, no tools) also runs:

```java
@AiService(model = "gpt-4o-mini")
interface Assistant {
    @SystemMessage("你是一个{role}助手")
    @UserMessage("请总结：{text}")
    String summarize(String role, String text);
}

Assistant ai = FrameworkUtil.create(Assistant.class, client);
String out = ai.summarize("法务", "合同正文……");
```

**Key points**:
- **Interface as service**: `@AiService` (interface-level model/temperature) + `@SystemMessage`/`@UserMessage` (`{paramName}` template placeholders) + `@Tool` (method signature auto-converted to JSON Schema on `ChatRequest.tools`); `FrameworkUtil.create(Class, client)` generates a JDK dynamic proxy in one line.
- **Return mapping**: `String` blocking text / `ChatResponse` raw response / `Stream<ChatStreamChunk>` streaming (bypasses the Advisor chain) / `record` structured (auto `responseFormat=json_schema`); `void` and unsupported return types fail at proxy creation.
- **Direct tool calls rejected, model-triggered only**: calling an `@Tool` method from business code throws `IllegalStateException`; after the model returns `tool_calls`, `ToolCallingAdvisor` closes the loop (execute → feed back → ask again), with built-in `ReflectionToolExecutor`.
- **Advisor hooks**: `before` in order / `around` nested (multiple `proceed` for tool loops and validation retries) / `after` reverse finally; order is execution order — recommended "cache → log → tool → validate".
- **SemanticCache**: `SemanticCache.builder().embedder(...)` (required) + `threshold/maxEntries/defaultTtlMillis/store/model`; in-memory vector index sequential scan + pluggable `CacheStore` payload backend (default `LruCacheStore`; pass rag's `RedisCacheStore` for distributed sharing).
- **Memory**: `@Memory` + `builder.memory(new InMemoryChatMemory(20))` auto-injects history and writes back on blocking turns (streaming does not write back).

**Offline**: the Advisor chain and proxy logic are locally unit-testable (inject fake `AiClient` / fake `SemanticCache`, zero real network); real chat and semantic similarity need usable platform client + embedding client (**API key**).

**Next steps**: the annotation family, Advisor constructors, semantic-cache internals and Redis in [docs/framework.md](framework.md); exact-cache vs Redis in [docs/cache.md](cache.md); structured output internals in [docs/structured-output.md](structured-output.md).

---

## Scenario 18: Document import + vector ingest + retrieval (v2.6.0)

**Goal**: use the v2.6.0 importers to read local Markdown / PDF into `Document`s, split, embed, and ingest, then answer from retrieval — covering the full chain `FileSystemLoader` → `TextSplitter` → `RagPipeline.ingest` → retrieval.

```java
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import com.sure.ai.ingest.FileSystemLoader;
import com.sure.ai.ingest.URLLoader;
import com.sure.ai.ingest.poi.PoiParsers;
import com.sure.ai.client.AiConfig;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.rag.splitter.MarkdownTextSplitter;

OpenAiClient client = new OpenAiClient(
        AiConfig.builder().apiKey(System.getenv("SURE_AI_OPENAI_API_KEY")).build());

// 1) Import: local files -> Documents with metadata (format=md/pdf, source=path)
FileSystemLoader loader = FileSystemLoader.createDefault();   // built-in TXT/MD/HTML/PDF
List<Document> files = loader.load(Path.of("docs/guide.md"));

// 2) Pipeline: chat and embedding reuse the same client; Markdown sources use MarkdownTextSplitter
RagPipeline pipeline = RagPipeline.builder()
        .chatClient(client)
        .chatModel(OpenAiModels.GPT_4O_MINI)
        .embeddingClient(client)
        .embeddingModel(OpenAiModels.TEXT_EMBEDDING_3_SMALL)
        .splitter(new MarkdownTextSplitter(800, 100))
        // .vectorStore(PgVectorStore.builder().table("docs").dimension(1024).build()) // external store
        .build();

// 3) Ingest: chunks named doc.id()#i, original metadata written along with each chunk
for (Document f : files) {
    int chunks = pipeline.ingest(f);
    System.out.println(f.id() + " → " + chunks + " 块");
}

// 4) Retrieve: recall topK relevant chunks (no score), or retrieveWithScores(...) with similarity
List<Document> hits = pipeline.retrieve("如何接入外部向量库？", 4);
System.out.println("命中 " + hits.size() + " 块");

client.close();
```

Variants for web / Office documents:

```java
// Web pages / online PDFs: URLLoader (falls back to Content-Type when there is no extension)
List<Document> pages = URLLoader.createDefault()
        .load(URI.create("https://example.com/spec.pdf"));

// DOCX/XLSX/PPTX: optional POI module (poi-ooxml 5.5.1 provided, declare it yourself)
FileSystemLoader office = FileSystemLoader.builder()
        .register(PoiParsers.docx())
        .register(PoiParsers.xlsx())
        .register(PoiParsers.pptx())
        .build();
List<Document> reports = office.load(Path.of("docs/report.docx"));
```

**Key points**:
- **Importers parse, they do not split**: `FileSystemLoader.load(Path)` / `URLLoader.load(URI)` return `List<com.sure.ai.rag.model.Document>` with metadata `source`/`loaded_at`/`format` (URLs also `content_type`).
- **Format routing**: built-in TXT/MD/HTML/PDF; parser picked by extension (URLs without suffix by Content-Type); unsupported formats throw `IllegalStateException`; empty files / PDFs without a text layer return an empty list (batch does not abort).
- **PDF is a limited text-layer extraction**: pure JDK hand-written, no PDFBox; only standard single-byte fonts' `Tj`/`TJ`; scans / encrypted / non-ASCII font maps / multi-column reading order are not handled (corrupt PDFs return empty rather than erroring).
- **Two ingest overloads**: `pipeline.ingest(Document)` keeps document metadata (chunks named `id#i`); `pipeline.ingest(sourceId, text)` is the quick plain-text ingest. Splitters are overridden via `RagPipeline.builder().splitter(...)`.
- **Retrieval entry points**: pipeline-level `retrieve(query, topK)` / `retrieveWithScores(query, topK)`; `VectorStore.similaritySearch(...)` is the lower-level library API. Switching to an external vector store (PGVector/Typesense/Cassandra/MongoDB/Neo4j — 14 total) only changes `.vectorStore(...)`; import and split code stay.

**Offline**: importers / splitters / `InMemoryVectorStore` are locally unit-testable (pure JDK parsing, zero real network); real embedding and Q&A need a usable OpenAI (or compatible) client (**API key**).

**Next steps**: importer positioning, per-format limits, and the `DocumentParser` extension point in [docs/ingest.md](ingest.md); the 14 vector-store protocol subsets and auth limits in [docs/vector-stores.md](vector-stores.md); the full RAG chain in [docs/rag.md](rag.md).

---

## Scenario 19: Offline quality regression with a golden dataset (v2.8.0)

**Goal**: record a real backend's responses as a JSONL baseline, then replay them **zero-network** with `TraceRegression` to stop "answers silently degrade after prompt/model changes". The whole scenario runs offline with scripted fake clients.

```java
import java.nio.file.Path;
import java.util.List;

import com.sure.ai.evals.EvalRunner;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.EvalContext;
import com.sure.ai.evals.metric.EvalMetric;
import com.sure.ai.evals.metric.EvalOutcome;
import com.sure.ai.evals.metric.MetricResult;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.trace.RecordingClient;
import com.sure.ai.evals.trace.TraceRegression;

EvalDataset dataset = EvalDataset.of("golden", List.of(
        EvalCase.of("c1", "法国首都是哪里？", "巴黎"),
        EvalCase.of("c2", "1+1等于几？", "2")));

// Placeholder metric: this scenario only verifies "replay matches recording", not text content
EvalMetric passthrough = new EvalMetric() {
    public String name() { return "passthrough"; }
    public MetricResult evaluate(EvalCase c, EvalOutcome o, EvalContext ctx) {
        return MetricResult.pass("ok");
    }
};

Path golden = Path.of("target/golden.jsonl");

// ① Record once: wrap the real (or scripted) backend with RecordingClient, append one JSONL line per request/response pair
EvalReport baseline;
try (RecordingClient recording = RecordingClient.record(realClient, golden);
        EvalRunner runner = EvalRunner.builder(recording, "my-model")
                .addMetric(passthrough).build()) {
    baseline = runner.run(dataset);
}

// ② Replay now: zero network, match by request (fallback to recording order)
EvalReport current;
try (RecordingClient replay = RecordingClient.replay(golden);
        EvalRunner runner = EvalRunner.builder(replay, "my-model")
                .addMetric(passthrough).build()) {
    current = runner.run(dataset);
}

// ③ Regression: mean degradation beyond tolerance (default 0.02) = regression; assertPassed() in CI
TraceRegression.RegressionResult result = TraceRegression.compare(baseline, current);
System.out.println(result.summary());
result.assertPassed();
```

**Key points**: `RecordingClient.record(delegate, path)` serializes each `chat()` request/response as one JSONL line (UTF-8 append); `RecordingClient.replay(path)` holds no delegate client — **fully zero network**. `TraceRegression` compares per-metric aggregated means; only `baseline - current > tolerance` counts as regression (exact equality does not); tolerance via `TraceRegression.builder().tolerance(0.01).tolerance("latency", 0.05).build()` globally or per metric.

**Offline**: swap `realClient` for a scripted `AiClient` (queue returning preset texts) and unit-test; see `sure-ai-examples`'s `RegressionDemo` (`java com.sure.ai.examples.ExamplesRunner regression`).

---

## Scenario 20: LLM-as-judge scoring + CI (v2.8.0)

**Goal**: run deterministic metrics over a batch of Q&As, ask a stronger judge model to score against a rubric, then gate quality in CI with `EvalThreshold`.

```java
import java.util.List;

import com.sure.ai.evals.EvalUtil;
import com.sure.ai.evals.assertion.EvalAssertions;
import com.sure.ai.evals.assertion.EvalThreshold;
import com.sure.ai.evals.dataset.EvalCase;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.ContainsMetric;
import com.sure.ai.evals.metric.ExactMatchMetric;
import com.sure.ai.evals.metric.JudgeMode;
import com.sure.ai.evals.metric.LlmJudgeMetric;
import com.sure.ai.evals.report.EvalReport;
import com.sure.ai.evals.report.EvalMarkdownReportWriter;

EvalDataset dataset = EvalDataset.of("qa", List.of(
        EvalCase.of("c1", "法国首都是哪里？", "巴黎"),
        EvalCase.of("c2", "用一句话介绍 Java", "Java 是一门面向对象的编程语言")));

// Judge model uses any AiClient (an offline scripted client here; swap in OpenAiClient etc. in production)
LlmJudgeMetric judge = LlmJudgeMetric.builder(judgeClient, "judge-model")
        .mode(JudgeMode.BINARY)                 // or SCALE_1_TO_5 / SCORE_100 / PAIRWISE
        .rubric("答案是否正确、简洁、无幻觉")
        .passThreshold(0.8)                      // default 0.6
        .build();

EvalReport report = EvalUtil.run(chatClient, "candidate-model", dataset,
        ExactMatchMetric.builder().build(),
        ContainsMetric.builder().ignoreCase(true).build(),
        judge);

System.out.println(new EvalMarkdownReportWriter().render(report));

// CI gate: mean or pass-rate below threshold throws AssertionError
EvalAssertions.assertMeets(report, EvalThreshold.builder()
        .putMean("exact_match", 0.8)
        .putPassRate("llm_judge", 0.8)
        .build());
```

**Key points**: judge failure / empty text / unparseable output defaults to `notApplicable` (NaN, excluded from aggregation) without aborting the batch; for determinism use `.onUnparseableDefaultScore(0.5)`. Three report writers: `EvalJsonReportWriter` / `EvalMarkdownReportWriter` / `EvalHtmlReportWriter` (self-contained HTML, no external links).

**Offline**: deterministic metrics are pure JDK, locally testable; the LLM judge runs offline with a scripted `AiClient` (queue returning YES/NO); see `sure-ai-examples`'s `EvalsDemo` (`java com.sure.ai.examples.ExamplesRunner evals`).

---

## Scenario 21: A/B comparison of two models (v2.8.0)

**Goal**: run two models (or two prompt versions) on the same dataset and quantify "which is really better" with `EvalComparison`.

```java
import com.sure.ai.evals.EvalComparison;
import com.sure.ai.evals.EvalRunner;
import com.sure.ai.evals.dataset.EvalDataset;
import com.sure.ai.evals.metric.ExactMatchMetric;
import com.sure.ai.evals.report.EvalReport;

EvalDataset dataset = ...;   // same dataset
ExactMatchMetric exact = ExactMatchMetric.builder().build();

EvalReport reportA;
try (EvalRunner runner = EvalRunner.builder(clientA, "model-A")
        .addMetric(exact).build()) {
    reportA = runner.run(dataset);
}
EvalReport reportB;
try (EvalRunner runner = EvalRunner.builder(clientB, "model-B")
        .addMetric(exact).build()) {
    reportB = runner.run(dataset);
}

EvalComparison cmp = EvalComparison.of(reportA, reportB);
System.out.println("每指标均分差(A-B): " + cmp.meanDiffs());
System.out.println("pairwise 胜 A / 平 / 胜 B = "
        + cmp.winsA() + " / " + cmp.ties() + " / " + cmp.winsB());
```

**Key points**: `meanDiffs()` is the per-metric mean difference (A−B); `winsA/ties/winsB` are per-case per-metric pairwise results (aligned by `caseId`, NaN skipped). Combined with an LLM-judge metric you get pairwise quality comparison of two models' answers.

---

## Scenario 22: Agent Skills / sub-agent delegation / tool catalog (v2.9.0)

**Goal**: distill domain knowledge into `SKILL.md` skill packs, expose specialist Agents as delegatable tools, discover and select tools by tag/risk in a tool catalog, and export everything to a ReAct runtime in one shot. Full design in [agent-skills.md](agent-skills.md).

```java
// 1) Load skills (local dir / classpath / explicit jar); a skill is a tool
List<Skill> skills = SkillLoader.loadFromClasspath("skills");
ToolRegistry registry = new ToolRegistry();
SkillTool.registerAll(skills, registry);   // no args -> L2 body; {"resource":"..."} -> L3 resource

// 2) Tool catalog: register tools with metadata, discover by tag/keyword/risk
ToolCatalog catalog = new ToolCatalog(registry);
catalog.register(
    ToolFunction.of("refund", "发起退款",
        "{\"type\":\"object\",\"required\":[\"orderId\"],"
        + "\"properties\":{\"orderId\":{\"type\":\"string\"}}}"),
    args -> refundService.refund(args.getString("orderId")),
    ToolMetadata.builder("refund").addTag("order").addTag("money")
        .riskLevel(RiskLevel.HIGH).build());
List<ToolEntry> safe = catalog.filterByRiskLevel(RiskLevel.LOW);
catalog.exportTo(registry, e -> e.metadata().riskLevel() != RiskLevel.CRITICAL);

// 3) Sub-agent delegation: wrap a specialist ReActAgent as the parent's tool
ReActAgent specialist = new ReActAgent(specialistClient, base, new ToolRegistry());
AgentRunner runner = AgentRunner.of(specialist, "specialist", "专职处理子任务");
AgentAsTool.builder("specialist", "需要专业子任务时委派",
        ChildAgentFactory.fixed(runner))
    .maxDepth(5)
    .memoryPolicy(MemoryPolicy.ISOLATED)
    .build()
    .register(registry);

// 4) The parent Agent runs its normal ReAct loop; skills and sub-agents are just tools
ReActAgent supervisor = new ReActAgent(superClient, base, registry);
String answer = supervisor.run("按技能包指引处理，并把专业部分交给专员");
```

**Key points**:
- Skills parse only L1 metadata at construction; body (L2) / resources (L3) load on demand; `SkillPaths.safeRelative` guards against path traversal; name conflicts and invalid frontmatter fail loudly, never silently skip.
- Sub-agent delegation defaults to max depth 5; repeated delegation on the same chain throws `DelegationCycleException`, over-depth throws `DelegationDepthExceededException`; child events bubble via `ForwardingDelegationListener`.
- Exposing the tool catalog and skills over MCP (`server/discover` + `skills/list` + `skills/get` + `resources/read`) is a runnable offline demo:

```bash
java com.sure.ai.examples.ExamplesRunner agentmarket   # Agent-ecosystem MCP full chain (offline)
```

---

## Appendix: the example runner

`sure-ai-examples` ships `ExamplesRunner`, runnable offline (no key → Fake branch automatically):

```bash
mvn -pl sure-ai-examples -am package -DskipTests
# with the classpath built:
java com.sure.ai.examples.ExamplesRunner knowledgebase   # knowledge-base RAG (accepts a local txt path)
java com.sure.ai.examples.ExamplesRunner gateway-multi   # three-platform same-question comparison
java com.sure.ai.examples.ExamplesRunner code-assistant  # streaming + tool calling
java com.sure.ai.examples.ExamplesRunner evals           # generic evals: dataset -> metrics -> report -> threshold assert (offline)
java com.sure.ai.examples.ExamplesRunner regression      # record -> replay -> TraceRegression golden regression (offline)
java com.sure.ai.examples.ExamplesRunner agentmarket     # Agent ecosystem: tool catalog + skills exposed via MCP (offline)
java com.sure.ai.examples.ExamplesRunner                 # print all available cases
```
