# sureai Cookbook（场景菜谱）

面向开发者的「照着抄」手册：每个场景给一段可直接编译运行的最小代码，附关键点与进阶方向。
所有代码块与当前源码 API 逐字对齐；示例工程见 `sure-ai-examples` 模块（`ExamplesRunner` 一键运行）。

> 约定：Key 从环境变量读取，统一前缀 `SURE_AI_<平台>_API_KEY`，例如 `SURE_AI_OPENAI_API_KEY`。
> 未配置 Key 的平台示例会打印一行提示并退出，不会发起网络请求。

---

## 5 分钟入门

**目标**：引入依赖、发第一条对话、跑通。

**1. 引入 BOM 统一版本，再按需选平台模块**

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

> 想一次引入全部 23 个平台 + RAG + Agent，用聚合模块 `sure-ai-all`（`type=pom`）替代上面的平台依赖。

**2. 最小 chat 代码**

```java
import com.sure.ai.openai.OpenAiUtil;

// 前置：export SURE_AI_OPENAI_API_KEY=sk-xxx
String reply = OpenAiUtil.chat("gpt-4o-mini", "用一句话介绍你自己。").firstText();
System.out.println(reply);
```

**关键点**：
- 静态工具类 `XxxUtil.chat(model, prompt)` 一行出结果；Key 缺省从环境变量自动读。
- 返回 `ChatResponse`，用 `firstText()` 取首段文本。

**进阶**：换成带配置的 Client（超时、限流、重试），见[场景 1](#场景-1单平台-chat含流式)与 [docs/observability.md](observability.md)。

---

## 场景 1：单平台 chat（含流式）

**目标**：用 Client 做同步与流式对话。

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

// 同步
ChatResponse resp = client.chat(OpenAiModels.GPT_4O_MINI, "你好");
System.out.println(resp.firstText());

// 流式：逐片打印增量文本
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

**关键点**：
- `ChatRequest.builder()` 链式组装 `model` / `messages` / `temperature` 等；`ChatMessage.user/system/assistant` 构造消息。
- 流式回调里只拼 `chunk.deltaText()`；结束片的 `finishReason` 非空表示流结束。

**进阶**：结构化输出见 [docs/structured-output.md](structured-output.md)；思考模式见 [docs/thinking.md](thinking.md)。

---

## 场景 2：多平台切换与统一网关（路由策略）

**目标**：一个请求透明路由到多家平台，支持轮询、显式指定与故障转移。

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

// 1. 注册多个平台 client（openAiClient / deepSeekClient 由各平台模块创建）
ClientRegistry registry = new ClientRegistry();
registry.register("openai", openAiClient);
registry.register("deepseek", deepSeekClient);

// 2. 路由链：显式平台 → 能力过滤 → 轮询；故障转移最多 3 次
RoutingStrategy strategy = new ExplicitRoutingStrategy(
        new CapabilityRoutingStrategy(new RoundRobinStrategy()));
FailoverConfig failover = FailoverConfig.builder().maxAttempts(3).build();
GatewayClient gateway = new GatewayClient(registry, strategy, failover);

// 3. 像普通 AiClient 一样调用（extra[platform] 强制打到指定平台）
ChatResponse resp = gateway.chat(ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("你好")))
        .extra("platform", "openai")
        .build());
```

**关键点**：
- `GatewayClient` 本身实现 `AiClient`，调用方无需感知背后有几家。
- 路由策略可自由组合：`ExplicitRoutingStrategy` / `CapabilityRoutingStrategy` / `RoundRobinStrategy` / `LowestCostStrategy` / `LowestLatencyStrategy` / `WeightedRoutingStrategy`。
- 4xx（鉴权/限流）默认不跨平台转移，5xx/超时/IO 异常才转移，避免「换家也复现」。

**进阶**：租户配额、密钥池轮转见 [docs/gateway.md](gateway.md)；离线对比三平台的完整可运行示例见 `sure-ai-examples` 的 `MultiPlatformGatewayDemo`。

---

## 场景 3：RAG 知识库问答（入库 + 检索 + 生成）

**目标**：把本地文档切分、向量化入库，再基于检索上下文问答。

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.pipeline.RagPipeline;

OpenAiClient client = new OpenAiClient(
        AiConfig.builder().apiKey(System.getenv("SURE_AI_OPENAI_API_KEY")).build());

// 对话与向量化复用同一客户端（OpenAI 同时实现 AiClient 与 EmbeddingClient）
RagPipeline pipeline = RagUtil.pipeline(client, client,
        OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL);

// 入库：自动分块 → 向量化 → 写入进程内向量库
int chunks = pipeline.ingest("sureai-intro",
        "sureai 是零第三方依赖的 Java 大模型工具库，支持流式对话、Function Calling 与 RAG。");

// 检索 + 生成：自动召回 topK 上下文拼进系统提示词
var answer = pipeline.ask("sureai 支持哪些能力？", 3);
System.out.println(answer.firstText());
System.out.println("已入库分块数：" + chunks + "，向量库规模：" + pipeline.vectorStore().size());

client.close();
```

**关键点**：
- `RagUtil.pipeline(chatClient, embeddingClient, chatModel, embeddingModel)` 一行装配；默认 `InMemoryVectorStore` + 递归字符分块。
- 自定义分块/向量库：用 `RagPipeline.builder().splitter(new FixedSizeTextSplitter(120, 20)).vectorStore(...)` 替换。
- 任意平台的「对话 client + 嵌入 client」可自由组合，RAG 管线与平台解耦。

**进阶**：外部向量库（Milvus/Qdrant/PgVector 等）、混合检索、Rerank 见 [docs/rag.md](rag.md) 与 [docs/vector-stores.md](vector-stores.md)；读本地文件、可离线跑通的完整示例见 `KnowledgeBaseDemo`。

---

## 场景 4：Agent 工具调用（Function Calling）

**目标**：注册自定义工具，让模型自主决定调用、回灌结果、再给出最终答案。

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

**关键点**：
- `ToolRegistry.register(ToolFunction.of(name, desc, jsonSchema), handler)`；`handler` 是 `ToolHandler`，入参 `JsonObject`、返回纯文本。
- `ReActAgent` 自动跑 Thought→Action→Observation 循环：模型返回 `tool_calls` 就执行并把 `tool` 消息回灌，直到给出纯文本答案。
- 内置 `CalculatorTool`（白名单四则运算，无 eval/反射）、`DateTimeTool`、`HttpTool`（带 SSRF 防护）。

**进阶**：HITL 审批、会话/长期记忆、检查点持久化、多 Agent 编排见 [docs/agent.md](agent.md) 与 [docs/agent-advanced.md](agent-advanced.md)；含流式与离线脚本模型的完整示例见 `CodeAssistantDemo`。

---

## 场景 5：异步 / 虚拟线程（v1.9.0 AsyncClients）

**目标**：把阻塞式调用放到 JDK 21 虚拟线程上并发打多家平台。

```java
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatMessage;

// 任意平台 client 自带 default 异步方法，走共享虚拟线程执行器
var f1 = openAiClient.chatAsync(ChatRequest.builder()
        .model("gpt-4o-mini").messages(List.of(ChatMessage.user("介绍 RAG"))).build());
var f2 = deepSeekClient.chatAsync(ChatRequest.builder()
        .model("deepseek-chat").messages(List.of(ChatMessage.user("介绍 RAG"))).build());

// 并发等待，两家一起回来
var both = f1.thenCombine(f2, (a, b) -> "openai=" + a.firstText() + " | deepseek=" + b.firstText());
System.out.println(both.get());
```

**关键点**：
- `chatAsync(ChatRequest)` 返回 `CompletableFuture<ChatResponse>`；`chatStreamAsync(req, consumer)` 流结束时以 `null` 完成。
- 任务跑在 `sureai-vt-` 命名的守护虚拟线程上，阻塞 IO 自动让出载体线程；异常原样进入未来异常完成态。
- 既有同步 API 完全不变，异步是 default 方法零成本获得。

**进阶**：自定义执行器 / 受控生命周期用 `AsyncAiClient` 包装，见 [docs/async.md](async.md)。

---

## 场景 6：可观测性（OTel 桥接 + Metrics）

**目标**：挂内置指标统计，或桥接到 OpenTelemetry GenAI 语义约定后端。

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.observability.AiMetrics;

AiMetrics metrics = new AiMetrics();
AiConfig config = AiConfig.builder()
        .apiKey(System.getenv("SURE_AI_OPENAI_API_KEY"))
        .maxRetries(2)
        .rateLimitQps(10)                 // 0=关闭
        .metricsCollector(metrics)        // 挂载内置零依赖指标
        .retryListener((attempt, status, ex, backoffMs, path) ->
                System.out.printf("retry #%d on %s status=%d%n", attempt, path, status))
        .build();

// 打完后读快照
var s = metrics.snapshot();
System.out.println("requests=" + s.totalRequests() + " failure=" + s.failureCount()
        + " avgMs=" + s.avgDurationMs());
```

桥接 OpenTelemetry（可选模块 `sure-ai-otel`）：

```java
import com.sure.ai.otel.OtelSupport;

AiConfig config = AiConfig.builder()
        .apiKey(key)
        .metricsCollector(OtelSupport.metricsCollector(mp, "openai", "chat"))
        .retryListener(OtelSupport.retryListener(mp, "openai", "chat"))
        .build();
```

**关键点**：
- 不配置 metrics / retryListener 时行为与未引入前完全一致，零额外开销。
- 可重试状态码 `429/5xx`，指数退避（`Retry-After` 优先），IO/中断不重试。
- Micrometer / Prometheus 桥接用 `MicrometerMetricsAdapter`。

**进阶**：完整指标映射表与重试语义见 [docs/observability.md](observability.md)。

---

## 场景 7：CLI 使用（sureai chat / stream / rag）

**目标**：不写 Java，终端里直接问答、流式、本地文档 RAG。

```bash
# 打 fat jar
mvn -pl sure-ai-cli -am package -DskipTests

# 一次性问答（默认 openai）
export SURE_AI_OPENAI_API_KEY=sk-xxx
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar chat "用一句话解释 RAG"

# 流式
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar stream "写一首短诗"

# 本地文档 RAG（不发起文档网络下载）
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar rag "公司成立于哪年？" --doc ./company.txt

# 切平台 / 交互 repl
java -jar sure-ai-cli/target/sure-ai-cli-2.0.0-SNAPSHOT.jar --provider deepseek repl
```

**关键点**：
- 子命令 `chat` / `stream` / `rag` / `list` / `repl`；全局选项 `--provider` / `--api-key` / `--model` / `--base-url`。
- RAG 流程：读本地文档 → 递归分块 → `InMemoryVectorStore` 入库 → 检索 topK=4 → 拼上下文生成。

**进阶**：环境变量表与退出码见 [docs/cli.md](cli.md)。

---

## 场景 8：生成工程（archetype）

**目标**：一键生成带 BOM、单平台 Client 调用、README 的 Hello World 工程。

```bash
mvn archetype:generate \
  -DgroupId=com.example \
  -DartifactId=my-ai-app \
  -Dversion=1.0.0-SNAPSHOT \
  -DarchetypeGroupId=io.github.tasure \
  -DarchetypeArtifactId=sure-ai-archetype \
  -DarchetypeVersion=2.0.0-SNAPSHOT
```

**关键点**：
- 生成产物：pom（import BOM）+ 一个可运行的 OpenAI 调用主类 + README + `.gitignore`。
- archetype 是应用层脚手架，不进入 `sure-ai-all` / `sure-ai-bom` 聚合链。

**进阶**：换平台时把生成类里的 `OpenAiClient` 换成目标平台模块即可，各平台 API 风格一致。

---

## 场景 9：Native 编译（GraalVM）

**目标**：把应用编译为无 JVM 依赖的本地可执行文件。

```bash
# 1) 打 fat jar（保留 META-INF/native-image/** AOT 元数据）
mvn -B -DskipTests package

# 2) native 编译
native-image \
  -jar sure-ai-examples/target/sure-ai-examples-2.0.0-SNAPSHOT.jar \
  -o sureai-demo
```

**关键点**：
- 全库已加固 AOT 元数据（`JsonMapper` / `JsonSchemaGenerator` 反射点 + `java.net.http` 协议），fat jar 自动内联 `native-image/**`。
- 编译参数：`-H:+AddAllCharsets --enable-url-protocols=https,http`。
- 仅在反射热点加载 classpath 资源时才需额外 `-H:IncludeResources`。

**进阶**：元数据清单与排障见 [docs/native-image.md](native-image.md)。

---

## 场景 10：GraphRAG 全局主题检索（知识图谱）

**目标**：在向量检索之外，用「实体—关系图谱 + 社区主题摘要」回答需要纵观全局、跨文档归纳的问题。源码内置 `com.sure.ai.rag.graph`，非外部依赖。

```java
import java.util.List;
import com.sure.ai.rag.graph.GraphRagIndexer;
import com.sure.ai.rag.graph.GraphRagRetriever;
import com.sure.ai.rag.graph.LlmCommunitySummarizer;
import com.sure.ai.rag.graph.LlmEntityRelationExtractor;
import com.sure.ai.rag.model.Document;

// 抽取器：LLM 把文本解析为「主体 | 关系 | 客体」三元组（异常/脏行容错跳过）
var extractor = LlmEntityRelationExtractor.builder()
        .chatClient(client).model("gpt-4o-mini").build();
// 社区摘要器：把一个社区的实体/关系归纳为一句主题（异常回退机械拼接）
var summarizer = LlmCommunitySummarizer.builder()
        .chatClient(client).model("gpt-4o-mini").build();

// 建图：抽取实体关系 → 标签传播社区发现 → 逐社区摘要
GraphRagIndexer indexer = GraphRagIndexer.builder()
        .extractor(extractor)
        .summarizer(summarizer)   // detector 缺省即 LabelPropagationCommunityDetector
        .build();
indexer.ingest(List.of(
        Document.of("doc-1", "sureai 由 TASure 发起，核心模块是 sure-ai-core。"),
        Document.of("doc-2", "sure-ai-core 提供 JDK HttpClient 与 JSON 解析，被 23 个平台模块复用。")));

// 检索：query 字面命中实体 → 定位社区 → 按命中数降序返回社区摘要
GraphRagRetriever retriever = GraphRagRetriever.builder().indexer(indexer).build();
for (Document d : retriever.retrieve("sure-ai-core 复用了哪些模块？", 3)) {
    System.out.println("社区#" + d.metadata().get("communityId") + " → " + d.text());
}
System.out.println("实体数=" + indexer.graph().entities().size()
        + "，社区数=" + indexer.communities().size());
```

**关键点**：
- `KnowledgeGraph` 实体名经 `normalizeName`（去空白、转小写）归一去重，线程安全。
- `GraphRagRetriever.retrieve` 是确定性的（按 query 命中实体数排序），检索阶段零额外 LLM 调用；只有建图（抽取 + 摘要）消耗 token。
- 与向量检索互补：向量擅长「找相关片段」，GraphRAG 擅长「跨文档全局主题」，可两路召回后合并。

**离线可跑性**：建图/摘要需 LLM（**需 API key**）；未配 Key 时抽取器与摘要器按既有容错降级（不写图、回退机械拼接），用 Fake client 可把整条管线跑通。

**进阶**：图谱内部结构见 [docs/rag.md](rag.md) 的 GraphRAG 章节；社区发现算法、与向量检索融合见 [docs/agent-advanced.md](agent-advanced.md) 同类检索增强讨论。

---

## 场景 11：Agent 人工审批（Human-in-the-Loop）

**目标**：高风险工具（如联网请求、写操作）执行前先暂停，交人工/策略审批，通过才继续。

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

// 策略：只有命中白名单的工具才需要审批；Handler：控制台交互读 y/n
ApprovalGate gate = new ApprovalGate(
        ToolNameApprovalPolicy.of("http_call"),
        new ConsoleApprovalHandler());

ChatRequest base = ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.system("你可以联网查资料。")))
        .build();

// 最长构造器传入 approvalGate（倒数第二个参数），LongTermMemory 传 null
ReActAgent agent = new ReActAgent(client, base, registry, new AgentListener() {
}, 5, Duration.ofSeconds(30), null, null, null, gate, null);

String answer = agent.run("帮我查一下 example.com 的首页标题。");
System.out.println(answer);
```

**关键点**：
- 每次工具执行前 `gate.requiresApproval(toolName, args)` 先过策略；命中则 `gate.request(ApprovalRequest.of(agentId, tool, args, desc, highRisk))` 阻塞等待 `ApprovalDecision`。
- 策略可换：`AllToolsApprovalPolicy` / `NeverApprovalPolicy` / `HighRiskApprovalPolicy` / `ToolNameApprovalPolicy.of(...)`；Handler 可换：`ConsoleApprovalHandler` / `AutoApprovalHandler` / `AutoRejectHandler` / `TimeoutApprovalHandler`，或自实现 `ApprovalHandler`。
- 拒绝后 Agent 不会报错中断，而是把「被拒绝」作为观察回灌模型，让它换方案或放弃。

**离线可跑性**：审批门本身纯本地；跑通完整循环仍需一个 chat client（**需 API key**，或用 Fake client）。

**进阶**：检查点持久化（审批后可断点续跑）、会话记忆见 [docs/agent.md](agent.md) 与 [docs/agent-advanced.md](agent-advanced.md)。

---

## 场景 12：Gateway 多供应商路由（权重 + 能力 + 故障转移）

**目标**：按权重分流、按能力过滤、失败自动切换供应商，调用方无感知。

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
// 同一平台多实例也可注册：第 2 参数 instanceId；第 5 参数声明能力，第 6 参数 weight
registry.register("openai", "primary", openAiClient,
        Set.of(Capability.CHAT, Capability.CHAT_STREAM), 2.0, "gpt-4o-mini");
registry.register("openai", "backup", openAiClient,
        Set.of(Capability.CHAT, Capability.CHAT_STREAM), 1.0, "gpt-4o-mini");
registry.register("deepseek", "default", deepSeekClient,
        Set.of(Capability.CHAT), 1.0, "deepseek-chat");

// 先按能力过滤候选，再按权重加权随机（权重越高被选中概率越大）
GatewayClient gateway = new GatewayClient(registry,
        new CapabilityRoutingStrategy(new WeightedRoutingStrategy()),
        FailoverConfig.builder().maxAttempts(3).build());

ChatResponse resp = gateway.chat(ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("用一句话介绍你自己。")))
        .build());
```

**关键点**：
- 路由策略可任意嵌套：`CapabilityRoutingStrategy`（只留具备所需能力的候选）包住 `WeightedRoutingStrategy`（按 weight 概率分流）；另有 `RoundRobinStrategy` / `LowestCostStrategy` / `LowestLatencyStrategy` / `ExplicitRoutingStrategy`。
- 同平台多实例靠 `instanceId` 区分，权重默认 1.0。
- 4xx（鉴权/限流）不跨家转移，5xx/超时/IO 异常才转移，最多 `maxAttempts` 次。

**离线可跑性**：路由/故障转移逻辑纯本地；要真出结果仍需各平台 client（**需 API key**），离线可用 Fake client 观察分流与 failover。

**进阶**：租户配额、密钥池轮转、延迟追踪见 [docs/gateway.md](gateway.md)；三平台同问对比的离线示例见 `sure-ai-examples` 的 `MultiPlatformGatewayDemo`。

---

## 场景 13：OTel GenAI 追踪接入（OtelSupport）

**目标**：把请求时长、token 用量、重试、Agent 事件桥接为 OpenTelemetry GenAI 语义约定指标，导出到自建后端。

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.otel.OtelSupport;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.MeterProvider;

// 由使用方自备 OTel SDK（SDK/导出器不随 sure-ai-otel 传递）
OpenTelemetry otel = ...; // 例：OtlpHttpExporter + BatchSpanProcessor 装配
MeterProvider mp = otel.getMeterProvider();

AiConfig config = AiConfig.builder()
        .apiKey(System.getenv("SURE_AI_OPENAI_API_KEY"))
        .metricsCollector(OtelSupport.metricsCollector(mp, "openai", "chat"))
        .retryListener(OtelSupport.retryListener(mp, "openai", "chat"))
        .build();

// Agent 场景再桥接事件流（可选）
var agentEventSink = OtelSupport.agentEventSink();
```

**关键点**：
- 映射到 `gen_ai.client.operation.duration`（DoubleHistogram）、`input_tokens` / `output_tokens`（LongCounter），公共属性 `gen_ai.operation.name` / `gen_ai.provider.name` / `gen_ai.request.model`。
- 传 `MeterProvider.noop()` 或 null 即全空操作、零开销；运行期未引 OTel SDK 时本模块不被加载。
- `sure-ai-otel` 仅依赖 OTel API（provided），SDK/导出器由你自己装配。

**离线可跑性**：指标桥接本身本地可测（用 `MeterProvider.noop()` 不报错）；真实导出需起 OTel collector（**按需**）。

**进阶**：完整指标映射表与重试语义见 [docs/observability.md](observability.md)；内置零依赖 `AiMetrics`（不接 OTel）见场景 6。

---

## 场景 14：CLI + RAG 本地离线问答

**目标**：不写 Java，用 `sureai-cli rag` 把本地文档喂进去直接问答，全程不下载文档。

```bash
# 打 fat jar
mvn -pl sure-ai-cli -am package -DskipTests

# 1. 摄入本地文档并提问（--doc 可多次，支持多文档）
export SURE_AI_OPENAI_API_KEY=sk-xxx
java -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar rag \
  "公司成立于哪年？主营业务是什么？" \
  --doc ./company.txt --doc ./history.txt

# 2. 流程：读本地 UTF-8 文本 → 递归分块 → InMemoryVectorStore 入库 → topK=4 检索 → 拼上下文生成
# 3. 换平台：--provider deepseek / --model xxx
java -jar sure-ai-cli/target/sure-ai-cli-2.2.0-SNAPSHOT.jar rag "..." --doc ./a.txt --provider deepseek
```

**关键点**：
- `rag` 子命令只读本机 `--doc` 指定的纯文本，**不发起任何文档网络下载**；向量库为进程内 `InMemoryVectorStore`，退出即销毁。
- 凭证优先级：`--api-key` > `SURE_AI_<平台>_API_KEY`；退出码 0 成功 / 1 输入错误 / 2 平台异常。
- 无 Key 时其余子命令（`list`）可离线跑；`rag` 的向量化与生成仍需一个可用 chat/embedding client（**需 API key**）。

**离线可跑性**：摄入/分块/检索管道本地完成；仅最终生成与向量化调用模型。

**进阶**：环境变量表、退出码、全部子命令见 [docs/cli.md](cli.md)；管线原理见 [docs/rag.md](rag.md)。

---

## 场景 15：多模态检索（图文混合入库与召回）

**目标**：把「文本 + 图片片段」作为一条多模态文档入库，文本向量化可检索；注入 `ImageEmbedder` 后图片也向量化。

```java
import java.util.List;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.TextPart;
import com.sure.ai.rag.RagUtil;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.strategy.MultimodalDocument;
import com.sure.ai.rag.strategy.MultimodalIngestor;
import com.sure.ai.rag.strategy.MultimodalRetriever;

// 文本向量化适配器（包一层你的 EmbeddingClient；EmbeddingResponse.embeddings() 即向量列表）
EmbeddingProvider textEmbed = text ->
        embeddingClient.embed("text-embedding-3-small", text).embeddings().get(0);

var ingestor = MultimodalIngestor.builder()
        .store(RagUtil.inMemoryStore())
        .textEmbeddingProvider(textEmbed)
        // 未注入 ImageEmbedder 时图片仅登记 mm_image_count 元数据，不报错
        .build();

MultimodalDocument doc = MultimodalDocument.of("card-1", List.of(
        TextPart.of("这是 sureai 架构图的说明：核心是 sure-ai-core。"),
        ImagePart.ofUrl("https://example.com/arch.png")));
ingestor.ingest(doc);

// 检索：返回纯文本 Document（Retriever 接口），或取完整图文 parts
var retriever = MultimodalRetriever.builder()
        .store(RagUtil.inMemoryStore())
        .textEmbeddingProvider(textEmbed)
        .ingestor(ingestor)
        .build();
for (MultimodalDocument md : retriever.retrieveMultimodal("架构核心是什么？", 3)) {
    System.out.println(md.text() + " 图片数=" + md.imageCount());
}
```

**关键点**：
- `MultimodalDocument.of(id, List<MessagePart>)` 聚合 `TextPart` / `ImagePart`；`text()` 聚合全部文本用于向量化，`images()` 取出全部图片片段。
- `MultimodalIngestor` 文本必入库；图片仅在注入 `ImageEmbedder`（`embed(ImagePart)->float[]`）时逐张向量化，否则只写 `mm_image_count` 元数据——可渐进启用。
- `retrieve()` 返回纯文本 `Document`（可当普通 Retriever 拼进 RAG 管线）；`retrieveMultimodal()` 返回带图片 parts 的完整文档。

**离线可跑性**：入库/检索管道本地可跑；文本向量化需 embedding client（**需 API key**），图片向量化需你实现的 `ImageEmbedder`。

**进阶**：多模态文档格式与图片理解见 [docs/multimodal.md](multimodal.md)；向量库与元数据过滤见 [docs/vector-stores.md](vector-stores.md)。

---

## 场景 16：Langfuse 原生观测接入（LangfuseExporters）

**目标**：把每次聊天调用以 trace + generation 直接上报到 Langfuse（LLM 观测平台），零依赖、无需自备 OTel SDK 或 Langfuse 官方 SDK。

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.deepseek.DeepSeekClient;
import com.sure.ai.deepseek.DeepSeekModels;
import com.sure.ai.otel.langfuse.LangfuseExporters;

// 前置：
//   export LANGFUSE_PUBLIC_KEY=pk-lf-...
//   export LANGFUSE_SECRET_KEY=sk-lf-...
//   export SURE_AI_DEEPSEEK_API_KEY=sk-xxx
// （可选 LANGFUSE_HOST=自托管地址，默认 https://cloud.langfuse.com）

// 关键一行：从环境变量构建 Langfuse 导出器并挂到 client
AiConfig config = AiConfig.builder()
        .apiKey(System.getenv("SURE_AI_DEEPSEEK_API_KEY"))
        .metricsCollector(LangfuseExporters.metricsCollectorFromEnv())
        .build();

DeepSeekClient client = new DeepSeekClient(config);
var resp = client.chat(DeepSeekModels.DEEPSEEK_CHAT, "用一句话回答：1+1等于几？");
System.out.println(resp.firstText());
client.close();
```

显式配置（自托管实例或指定环境标签）：

```java
import com.sure.ai.otel.langfuse.LangfuseConfig;
import com.sure.ai.otel.langfuse.LangfuseExporters;

AiConfig config = AiConfig.builder()
        .apiKey(key)
        .metricsCollector(LangfuseExporters.metricsCollector(
                LangfuseConfig.of("https://cloud.langfuse.com", "pk-lf-...", "sk-lf-...", "production")))
        .build();
```

**关键点**：
- `LangfuseExporters.metricsCollectorFromEnv()` / `metricsCollector(LangfuseConfig)` 返回标准 `MetricsCollector`，挂到 `AiConfig.metricsCollector(...)` 即可；一次逻辑调用 = 一个 `trace` + 一个 `generation`（含耗时、model、token 用量），重试追加为 `event-create`。
- 认证为 HTTP Basic `base64(pk:sk)`，POST 到 `/api/public/ingestion`；环境变量官方名优先、sureai 前缀（`SURE_AI_LANGFUSE_*`）兜底。
- **无感降级**：`LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` 任一缺失时导出器为空操作，不发起任何网络请求、不影响正常对话；发送失败仅记 warning、绝不抛出。

**离线可跑性**：导出器本身本地可测（未配密钥即空操作、不报错）；真实上报需可用 Langfuse 项目密钥。

**进阶**：环境变量表、事件映射表与认证说明见 [docs/observability.md](observability.md) 的「Langfuse 原生导出（v2.4.0）」小节；OTel GenAI 指标桥接见场景 13，内置零依赖 `AiMetrics` 见场景 6。

---

## 场景 17：声明式 AiService + Advisor 链装配（v2.5.0）

**目标**：用一个带注解的 Java 接口当 AI 服务（接口即服务），并把「语义缓存 → 日志 → 工具循环 → 结构化校验」四件套按序装配到一条 Advisor 链上。

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

// 结构化输出目标 record
record Product(String name, int stock) {}

@AiService(model = "gpt-4o-mini")
interface Catalog {

    @UserMessage("{q}")
    Product lookup(String q);

    @Tool(description = "查库存")
    default int stockOf(String item) {
        return queryStockDb(item);   // 你的真实库存查询
    }
}

// 1) 语义缓存：embedding 相似度阈值命中（embedder 为任意 core EmbeddingClient）
SemanticCache cache = SemanticCache.builder()
        .embedder(embeddingClient)
        .threshold(0.85)                 // 默认 0.85，[0,1]
        .build();

// 2) Advisor 链顺序：缓存短路 → 日志 → 工具循环 → 结构化自纠
List<Advisor> advisors = List.of(
        new SemanticCacheAdvisor(cache),
        new LoggingAdvisor(),
        new ToolCallingAdvisor(),                 // 默认 maxIterations=5
        new StructuredOutputValidationAdvisor());  // 默认 maxRetries=2

// 3) 一行装配
Catalog ai = FrameworkUtil.builder(Catalog.class, client)
        .advisors(advisors)
        .build();

// 4) 调用：模型可自动调 stockOf；输出不合规会自纠重写；问过的同义问题走缓存
Product p = ai.lookup("查一下《深入理解计算机系统》的库存");
System.out.println(p.name() + " 库存=" + p.stock());
```

最小版（不要 Advisor、不要工具）也能跑：

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

**关键点**：
- **接口即服务**：`@AiService`（接口级模型/温度）+ `@SystemMessage`/`@UserMessage`（`{paramName}` 模板占位符）+ `@Tool`（方法签名自动转 JSON Schema 挂 `ChatRequest.tools`）；`FrameworkUtil.create(Class, client)` 一行生成 JDK 动态代理。
- **返回映射**：`String` 阻塞文本 / `ChatResponse` 原始响应 / `Stream<ChatStreamChunk>` 流式（不经过 Advisor 链）/ `record` 结构化（自动挂 `responseFormat=json_schema`）；`void` 与不支持的返回类型在创建代理期即报错。
- **工具直调拒绝、模型侧触发**：业务代码直接调 `@Tool` 方法会抛 `IllegalStateException`；模型返回 `tool_calls` 后由 `ToolCallingAdvisor` 闭环（执行→回填→再问模型），内置 `ReflectionToolExecutor` 反射执行。
- **Advisor 三钩子**：`before` 正序 / `around` 嵌套（可多次 `proceed` 实现工具循环与校验重试）/ `after` 逆序 finally；顺序即执行顺序，推荐「缓存→日志→工具→校验」。
- **SemanticCache**：`SemanticCache.builder().embedder(...)`（必填）+ `threshold/maxEntries/defaultTtlMillis/store/model`；内存向量索引顺序扫描 + 可插拔 `CacheStore` 载荷后端（缺省 `LruCacheStore`，传 rag 的 `RedisCacheStore` 即分布式共享）。
- **记忆**：`@Memory` + `builder.memory(new InMemoryChatMemory(20))` 自动注入历史并回写阻塞轮（流式不回写）。

**离线可跑性**：Advisor 链与代理逻辑本地可单测（注入假 `AiClient` / 假 `SemanticCache`，零真实网络）；真实对话与语义相似度需可用平台 client + embedding client（**需 API key**）。

**进阶**：注解族、Advisor 四件套构造参数、语义缓存原理与 Redis 组合见 [docs/framework.md](framework.md)；精确缓存 vs Redis 选型见 [docs/cache.md](cache.md)；结构化输出底层见 [docs/structured-output.md](structured-output.md)。

---

## 场景 18：文档导入 + 向量入库 + 检索（v2.6.0）

**目标**：用 v2.6.0 的文档导入器把本地 Markdown / PDF 读成 `Document`，切块、向量化入库，
再基于检索问答——覆盖 `FileSystemLoader` → `TextSplitter` → `RagPipeline.ingest` → 检索一条完整链路。

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

// 1) 导入：本地文件 → 带元数据的 Document（format=md/pdf，source=路径）
FileSystemLoader loader = FileSystemLoader.createDefault();   // 内置 TXT/MD/HTML/PDF
List<Document> files = loader.load(Path.of("docs/guide.md"));

// 2) 管线：对话与向量化复用同一 client；Markdown 源用 MarkdownTextSplitter 结构化切分
RagPipeline pipeline = RagPipeline.builder()
        .chatClient(client)
        .chatModel(OpenAiModels.GPT_4O_MINI)
        .embeddingClient(client)
        .embeddingModel(OpenAiModels.TEXT_EMBEDDING_3_SMALL)
        .splitter(new MarkdownTextSplitter(800, 100))
        // .vectorStore(PgVectorStore.builder().table("docs").dimension(1024).build()) // 换外部库
        .build();

// 3) 入库：按 doc.id()#i 切块命名，原文元数据随分块一并写入
for (Document f : files) {
    int chunks = pipeline.ingest(f);
    System.out.println(f.id() + " → " + chunks + " 块");
}

// 4) 检索：召回 topK 相关块（不含得分），或 retrieveWithScores(...) 含相似度
List<Document> hits = pipeline.retrieve("如何接入外部向量库？", 4);
System.out.println("命中 " + hits.size() + " 块");

client.close();
```

接入网络文档 / Office 文档的变体：

```java
// 网页 / 在线 PDF：URLLoader（无扩展名时按 Content-Type 兜底）
List<Document> pages = URLLoader.createDefault()
        .load(URI.create("https://example.com/spec.pdf"));

// DOCX/XLSX/PPTX：可选 POI 模块（poi-ooxml 5.5.1 provided，需自行声明依赖）
FileSystemLoader office = FileSystemLoader.builder()
        .register(PoiParsers.docx())
        .register(PoiParsers.xlsx())
        .register(PoiParsers.pptx())
        .build();
List<Document> reports = office.load(Path.of("docs/report.docx"));
```

**关键点**：
- **导入器只解析、不切块**：`FileSystemLoader.load(Path)` / `URLLoader.load(URI)` 返回
  `List<com.sure.ai.rag.model.Document>`，metadata 含 `source`/`loaded_at`/`format`（URL 另含 `content_type`）。
- **格式路由**：默认内置 TXT/MD/HTML/PDF；按扩展名（URL 无后缀时按 Content-Type）自动选解析器；
  不支持的格式抛 `IllegalStateException`，空文件 / 无文本层 PDF 返回空列表（不中断批量）。
- **PDF 为有限文本层提取**：纯 JDK 手写、不引 PDFBox，仅覆盖标准单字节字体的 `Tj`/`TJ`；
  扫描件 / 加密 / 非 ASCII 字体映射 / 多栏阅读顺序均不处理（损坏 PDF 返回空而非报错）。
- **入库两条重载**：`pipeline.ingest(Document)` 保留文档元数据（按 `id#i` 命名分块）；
  `pipeline.ingest(sourceId, text)` 为纯文本快速入库。分块器经 `RagPipeline.builder().splitter(...)` 覆盖。
- **检索入口**：管线级 `retrieve(query, topK)` / `retrieveWithScores(query, topK)`；
  `VectorStore.similaritySearch(...)` 是库级别低层接口。换外部向量库（PGVector/Typesense/
  Cassandra/MongoDB/Neo4j 等 14 种）只需改 `.vectorStore(...)`，导入与切块代码不变。

**离线可跑性**：导入器 / 分块 / `InMemoryVectorStore` 本地可单测（解析逻辑纯 JDK，零真实网络）；
真实向量化与问答需可用 OpenAI（或兼容平台）client（**需 API key**）。

**进阶**：导入器定位、各格式限制与 `DocumentParser` 扩展点见 [docs/ingest.md](ingest.md)；
14 种向量库协议子集与鉴权限制见 [docs/vector-stores.md](vector-stores.md)；RAG 全链路见
[docs/rag.md](rag.md)。

---

## 附：示例运行器

`sure-ai-examples` 模块内置 `ExamplesRunner`，离线即可跑（无 Key 自动走 Fake 分支）：

```bash
mvn -pl sure-ai-examples -am package -DskipTests
# 构造好 classpath 后：
java com.sure.ai.examples.ExamplesRunner knowledgebase   # 知识库 RAG（可传本地 txt 路径）
java com.sure.ai.examples.ExamplesRunner gateway-multi   # 三平台同问对比
java com.sure.ai.examples.ExamplesRunner code-assistant  # 流式 + 工具调用
java com.sure.ai.examples.ExamplesRunner                 # 打印全部可用 case
```
