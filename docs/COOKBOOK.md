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
