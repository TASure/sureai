# 生产级 Agent 能力（v1.7.0）

在 [ReAct 多工具循环](agent.md) 之上，`sure-ai-agent` v1.7.0 为编排器补齐四组生产环境必备能力：
**检查点持久化**、**流式事件**、**HITL（Human-in-the-the-loop）审批**、**长期记忆**。
四组能力全部以「可选注入」方式接入：构造器传 `null` 即关闭，关闭后行为与历史版本逐字节一致；
模块仍只依赖 `sure-ai-core`，与具体平台解耦。

四组能力与既有 `ReActAgent` / `PlanExecuteAgent` 的关系：

| 能力 | 包 | 作用对象 | 默认行为 |
|---|---|---|---|
| 检查点持久化 | `com.sure.ai.agent.checkpoint` | `ReActAgent`（9 参 / 11 参构造器） | 不注入不持久化 |
| 流式事件 | `com.sure.ai.agent.event` | `AgentListener` 桥接 → 任意订阅方 | 不注入不产生事件 |
| HITL 审批 | `com.sure.ai.agent.approval` | `ReActAgent.executeTool` | 不注入不审批，工具直跑 |
| 长期记忆 | `com.sure.ai.agent.memory.longterm` | `ReActAgent.run` 前后 | 不注入不跨会话记忆 |

> 既有 `ConversationMemory`（会话内环形窗口）与新增的长期记忆互不干扰：前者管「本轮会话上下文」，后者管「跨会话事实沉淀」。

---

## 1. 检查点持久化

把某一时刻的编排状态（对话历史 + 已完成迭代数 + 最终答案 + 时间戳 + 元数据）序列化为快照，
崩溃 / 重启后可从断点**重放式恢复**——模型看到完整历史（含已完成的工具调用与结果），
不会重复已完成动作，而是从断点处继续 `run()`。

### 快速上手

```java
import com.sure.ai.agent.checkpoint.AgentCheckpointer;
import com.sure.ai.agent.checkpoint.CheckpointStore;
import com.sure.ai.agent.checkpoint.InMemoryCheckpointStore;

CheckpointStore store = new InMemoryCheckpointStore();

// 注入 store + sessionId：run 开头 / 每轮迭代后 / 结束自动落盘
ReActAgent agent = new ReActAgent(client, baseRequest, registry,
        null, ReActAgent.DEFAULT_MAX_ITERATIONS, ReActAgent.DEFAULT_TIMEOUT,
        /*memory*/ null, /*checkpointStore*/ store, /*sessionId*/ "sess-001");

agent.run("帮我查一下订单 A100 的状态");

// 进程崩溃 / 重启后，用同配置模板 agent 从断点重放式继续（不追加新用户消息）
String resumed = AgentCheckpointer.resume(store, "sess-001", agent);
```

也可以手工创建 / 保存 / 加载初始检查点：

```java
// 构建初始检查点 = baseRequest 模板消息 + 会话记忆 + 当轮用户消息，iteration=0
AgentCheckpoint cp = AgentCheckpointer.create("sess-001", agent, "帮我查一下订单 A100 的状态");
AgentCheckpointer.save(store, cp);

Optional<AgentCheckpoint> loaded = AgentCheckpointer.load(store, "sess-001");
```

### CheckpointStore 与内置实现

`CheckpointStore` 是按 `sessionId` 存取的 SPI：

| 方法 | 说明 |
|---|---|
| `void save(AgentCheckpoint checkpoint)` | 保存（覆盖）指定会话的检查点 |
| `Optional<AgentCheckpoint> load(String sessionId)` | 加载，不存在返回 `empty` |
| `void delete(String sessionId)` | 删除，不存在时静默忽略 |
| `List<String> listSessions()` | 列出全部会话标识（排序） |

| 实现 | 构造 | 说明 |
|---|---|---|
| `InMemoryCheckpointStore` | `new InMemoryCheckpointStore()` | 基于 `ConcurrentHashMap`，进程内有效、重启即失；适用于测试与短期会话，线程安全 |
| `FileCheckpointStore` | `new FileCheckpointStore(String dir)` 或 `new FileCheckpointStore(Path dir)` | 每会话一个 JSON 文件 `{dir}/{sessionId}.json`，目录不存在自动创建 |

`FileCheckpointStore` 内置**路径穿越防护**：`sessionId` 仅保留 `[A-Za-z0-9_-]`，其余字符替换为 `_`；
规范化后的路径必须仍位于根目录之下，否则抛 `AiException`。

```java
CheckpointStore fileStore = new FileCheckpointStore("/var/sureai/checkpoints");
// 落盘：/var/sureai/checkpoints/sess-001.json
```

### AgentCheckpoint 数据结构

`AgentCheckpoint` 是不可变 record，通过 `builder()` 构建：

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | `String` | 会话标识（非空） |
| `history` | `List<ChatMessage>` | 对话历史快照（防御性拷贝） |
| `iteration` | `int` | 已完成迭代数 |
| `finalAnswer` | `String` | 最终答案（进行中为 `null`） |
| `createdAtEpochMs` | `long` | 创建时间戳（毫秒） |
| `metadata` | `JsonObject` | 自定义元数据（缺省为空对象） |

`CheckpointSerializer`（静态工具）负责与 JSON 互转：`toJson(AgentCheckpoint)` / `fromJson(String)`。
由于 `ChatMessage` 是 final 类，序列化手写映射 `role / content / toolCallId / toolCalls(id/name/argumentsJson)` 五个恢复相关字段；
多模态 parts 与 reasoningContent 不参与检查点恢复。

### 保存时机

向 `ReActAgent` 注入 `CheckpointStore` + `sessionId`（两者均非 null）后自动落盘：

- **run 开头**：`iteration=0`，`finalAnswer=null`；
- **每轮工具迭代后**：`iteration=n`，`finalAnswer=null`（中间快照）；
- **run 结束**：模型给出文本答案时，`iteration=完成轮数`，`finalAnswer=最终答案`。

> 异常中断（如达到 `maxIterations` 抛 `AiException`）时不强制落盘最终检查点；上一个中间快照即断点。

### 重放式恢复语义

`AgentCheckpointer.resume(store, sessionId, agent)` 的恢复流程：

1. 加载检查点，取其 `history`；
2. 去掉与 `agent.baseRequestMessages()` 相同的前缀，把**尾部**（记忆 + 当轮用户消息 + 已执行工具调用/结果）灌入一个新的 `InMemoryConversationMemory`；
3. 调用 `agent.withMemory(memory).run()` 继续编排——不追加新的用户消息（断点处的用户消息已在历史中）。

`withMemory(ConversationMemory)` 返回一个其余配置完全相同的新 `ReActAgent`，专供检查点恢复使用。

---

## 2. 流式事件

把同步的 `AgentListener` 回调桥接为一组结构化 `AgentEvent`，经多订阅者广播器投递给前端 / SSE / 埋点，
**无需改动 `ReActAgent`**。

### 快速上手

```java
import com.sure.ai.agent.event.AgentEventPublisher;
import com.sure.ai.agent.event.StreamingAgentListener;

AgentEventPublisher publisher = new AgentEventPublisher();
// 订阅：接日志、进度条、或 SSE 写出
publisher.subscribe(event -> System.out.println(event.type() + " @ " + event.timestampEpochMs()));

// StreamingAgentListener 把既有 AgentListener 回调桥接为事件，再交给 ReActAgent
AgentListener listener = new StreamingAgentListener("agent-1", publisher);

ReActAgent agent = new ReActAgent(client, baseRequest, registry,
        listener, ReActAgent.DEFAULT_MAX_ITERATIONS, ReActAgent.DEFAULT_TIMEOUT);

agent.run("查一下西安天气");
```

`AgentEventPublisher` 同时实现 `AgentEventSink`，所以也可直接用 `new StreamingAgentListener("agent-1", publisher)` 这个便捷构造。

### 事件类型表

所有事件都是不可变 record，必须提供 `type()`（稳定类型字符串）与 `timestampEpochMs()`。

| 事件 record | `type()` | 字段 | 触发时机 |
|---|---|---|---|
| `StepStartedEvent` | `step.started` | `agentId, iteration, description` | Plan-and-Execute 某一步骤开始 |
| `StepCompletedEvent` | `step.completed` | `agentId, iteration, result` | 某一步骤完成 |
| `ToolCalledEvent` | `tool.called` | `agentId, toolName, arguments(JsonObject)` | 模型请求调用工具，参数解析为对象 |
| `ToolCompletedEvent` | `tool.completed` | `agentId, toolName, result, success(boolean)` | 工具执行完成（`success` 由错误前缀判定） |
| `ThoughtEvent` | `thought` | `agentId, thought` | 模型给出思考文本 |
| `FinalAnswerEvent` | `final.answer` | `agentId, answer` | 编排结束，最终答案 |
| `TokenDeltaEvent` | `token.delta` | `agentId, delta` | **预留**（见下） |
| `AgentErrorEvent` | `agent.error` | `agentId, error, message` | 工具执行异常等 |

桥接映射关系（`StreamingAgentListener`）：`onThought→ThoughtEvent`、`onToolCall→ToolCalledEvent`、
`onToolResult→ToolCompletedEvent`、`onFinish→FinalAnswerEvent`、`onError→AgentErrorEvent`、
`onStepStart/onStepComplete→StepStartedEvent/StepCompletedEvent`。

### SSE 输出

`AgentEventSseWriter.toSse(event)` 把一帧事件序列化为标准 SSE；`doneMarker()` 返回流结束标记：

```java
String frame = AgentEventSseWriter.toSse(new ToolCalledEvent(
        "agent-1", "get_weather", Json.object().put("city", "西安"),
        System.currentTimeMillis()));
out.write(frame);
out.write(AgentEventSseWriter.doneMarker());
```

输出形如：

```
event: tool.called
data: {"agentId":"agent-1","toolName":"get_weather","arguments":{"city":"西安"},"type":"tool.called","timestampEpochMs":1722300000000}

event: final.answer
data: {"agentId":"agent-1","answer":"西安：晴 26℃","type":"final.answer","timestampEpochMs":1722300005000}

data: [DONE]
```

其中 `data` 为事件 record 全部字段（含 `type` 与 `timestampEpochMs`）的 JSON。

### TokenDelta 预留说明

`TokenDeltaEvent`（`token.delta`）目前**不会**由 `StreamingAgentListener` 桥接产生——因为 `ReActAgent`
走的是**非流式** `client.chat(...)`，没有逐 token 增量。该事件为将来接入流式 chat 预留，
当前版本订阅 `token.delta` 收不到任何回调。

### 广播器语义

`AgentEventPublisher` 内部用 `CopyOnWriteArrayList`：发布无锁、订阅变更写时复制；
按订阅顺序同步遍历，**单个订阅者抛异常会被隔离**，不影响其余订阅者。
方法：`subscribe(AgentEventSink)` / `unsubscribe(AgentEventSink)` / `publish(AgentEvent)` / `clear()` / `subscriberCount()`。

---

## 3. HITL 审批

在工具真正执行前加一道人工 / 自动审批闸门：**策略判断要不要审批，handler 决定批不批**。
拒绝或超时则不执行工具，把提示文本回灌模型自我修正。

### 快速上手

```java
import com.sure.ai.agent.approval.ApprovalGate;
import com.sure.ai.agent.approval.ToolNameApprovalPolicy;
import com.sure.ai.agent.approval.ConsoleApprovalHandler;
import com.sure.ai.agent.approval.TimeoutApprovalHandler;
import java.time.Duration;

// 只对转账 / 删单这类工具要求审批；命中后控制台 y/n，30s 无人应答按超时处理
ApprovalGate gate = new ApprovalGate(
        ToolNameApprovalPolicy.of("payment_transfer", "delete_order"),
        new TimeoutApprovalHandler(new ConsoleApprovalHandler(), Duration.ofSeconds(30)));

ReActAgent agent = new ReActAgent(client, baseRequest, registry,
        null, ReActAgent.DEFAULT_MAX_ITERATIONS, ReActAgent.DEFAULT_TIMEOUT,
        /*memory*/ null, /*checkpointStore*/ null, /*sessionId*/ null,
        /*approvalGate*/ gate, /*longTermMemory*/ null);

agent.run("把订单 A100 的 100 元转给张三");
```

`ApprovalGate` 组合一个 `ApprovalPolicy` 与一个 `ApprovalHandler`：
- `requiresApproval(toolName, args)` 由策略判断；
- `request(ApprovalRequest)` 真正发起审批——**策略判否时短路直接返回 `APPROVED`，绝不触发 handler**。

### 审批策略表（ApprovalPolicy）

策略只回答「要不要审批」，可按工具名 / 参数 / 风险等级自由组合，是 `@FunctionalInterface`。

| 实现 | 获取方式 | 行为 |
|---|---|---|
| `AllToolsApprovalPolicy` | `AllToolsApprovalPolicy.instance()` | 所有工具调用都要求审批 |
| `NeverApprovalPolicy` | `NeverApprovalPolicy.instance()` | 一律不审批（`ApprovalGate` 默认策略） |
| `ToolNameApprovalPolicy` | `ToolNameApprovalPolicy.of("a","b")` 或 `new ToolNameApprovalPolicy(Set.of(...))` | 仅对精确匹配（大小写敏感）的工具名审批 |
| `HighRiskApprovalPolicy` | `new HighRiskApprovalPolicy()` 或 `new HighRiskApprovalPolicy(namePrefixes, amountKeys)` | 按高危前缀 / 金额字段自动判定 |

`HighRiskApprovalPolicy` 默认命中规则：
- 工具名以 `payment / transfer / withdraw / delete / remove / write / update / exec / shell` 开头（大小写不敏感）；
- 参数中出现 `amount / money / balance / price / total` 等键，且其数值 `> 0`（视为涉及资金）。

前缀集合与金额键集合均可通过构造器自定义。

### 审批 handler 表（ApprovalHandler）

handler 接收 `ApprovalRequest` 并**阻塞等待**最终决定，返回 `ApprovalDecision`。

| 实现 | 获取方式 | `name()` | 行为 |
|---|---|---|---|
| `AutoApprovalHandler` | `AutoApprovalHandler.instance()` | `auto` | 立即通过（测试 / 受信环境） |
| `AutoRejectHandler` | `AutoRejectHandler.instance()` / `AutoRejectHandler.withReason("...")` | `auto-reject` | 立即拒绝（仅测试拒绝路径） |
| `ConsoleApprovalHandler` | `new ConsoleApprovalHandler()` | `console` | 打印审批请求到 stdout，从 stdin 读 `y/yes/是` 通过，其余拒绝；**会阻塞等待输入** |
| `TimeoutApprovalHandler` | `new TimeoutApprovalHandler(delegate, Duration)` | `timeout(delegate)` | 包装另一个 handler，超时自动产生 `TIMEOUT` 决定 |

`ConsoleApprovalHandler` 无超时控制，生产环境请用 `TimeoutApprovalHandler` 包装；
被包装 handler 在 ForkJoinPool 异步执行，超时后 best-effort 取消后台任务。

### 拒绝 / 超时回灌语义

`ReActAgent.executeTool` 在审批命中后：

- `REJECTED` → 不执行工具，回灌模型文本 `用户拒绝执行工具 <name>：<reason>`；
- `TIMEOUT` → 不执行工具，回灌 `审批超时，跳过工具 <name>`；
- `APPROVED` → 继续执行工具。

回灌文本作为 `tool` 角色结果交给模型，由模型自我修正（换工具 / 换参数 / 放弃），编排不中断。

`ApprovalStatus` 三态枚举：`APPROVED` / `REJECTED` / `TIMEOUT`。
`ApprovalDecision` 提供静态工厂 `approved()` / `approved(reason)` / `rejected(reason)` / `timeout()`。

---

## 4. 长期记忆

跨会话沉淀与召回：run 前把相关历史事实以 system 消息注入上下文，run 后把本轮对话提取为长期记忆。
与会话内 `ConversationMemory` 互补。

### 快速上手

```java
import com.sure.ai.agent.memory.longterm.DefaultMemoryExtractor;
import com.sure.ai.agent.memory.longterm.InMemoryMemoryStore;
import com.sure.ai.agent.memory.longterm.LengthBasedSummarizer;
import com.sure.ai.agent.memory.longterm.LongTermMemory;
import com.sure.ai.agent.memory.longterm.NoopMemoryEmbedder;

LongTermMemory ltm = new LongTermMemory(
        new InMemoryMemoryStore(),     // store：内存向量存储
        new DefaultMemoryExtractor(),   // extractor：默认确定性提取
        NoopMemoryEmbedder.instance(), // embedder：返回 null → 降级文本匹配
        new LengthBasedSummarizer());  // summarizer：长度截断摘要

// 全传 null 等价于上面这一行（全部走默认实现）：
// LongTermMemory ltm = new LongTermMemory(null, null, null, null);

ReActAgent agent = new ReActAgent(client, baseRequest, registry,
        null, ReActAgent.DEFAULT_MAX_ITERATIONS, ReActAgent.DEFAULT_TIMEOUT,
        /*memory*/ null, /*checkpointStore*/ null, /*sessionId*/ "sess-001",
        /*approvalGate*/ null, /*longTermMemory*/ ltm);

agent.run("我叫王五，以后叫我老王");
// run 后自动 remember：沉淀「用户说：我叫王五…」与「助手答：…」
```

`LongTermMemory` 门面方法：

| 方法 | 说明 |
|---|---|
| `void remember(List<ChatMessage> messages, String sessionId)` | 提取对话为记忆条目，向量化后落盘 |
| `List<MemoryEntry> recall(String query, int k)` | 按查询召回 Top-K 相关记忆 |
| `void forget(String id)` | 按 ID 遗忘一条 |
| `List<MemoryEntry> all()` | 全部记忆快照 |
| `void clear()` | 清空 |
| `MemoryStore store()` / `MemorySummarizer summarizer()` | 暴露底层组件（高级用法） |

`DEFAULT_RECALL_K = 3`：`ReActAgent` run 前固定按 `k=3` 召回。

### 组件接口表

| 组件 | 类型 | 说明 |
|---|---|---|
| `MemoryStore` | 接口 | 键值 CRUD：`put/get/delete/all/clear`（以条目 `id` 为主键） |
| `VectorMemoryStore` | 接口（extends `MemoryStore`） | 增加 `search(float[] queryVector, int k)` 余弦 Top-K 与 `searchByText(String query, int k)` 子串匹配 |
| `InMemoryMemoryStore` | `implements VectorMemoryStore` | 基于 `ConcurrentHashMap`；`search` 全量余弦 Top-K，`searchByText` 大小写不敏感包含匹配（按命中次数降序、再按创建时间升序） |
| `MemoryEmbedder` | `@FunctionalInterface` | `float[] embed(String text)`；返回 `null` 表示不向量化 |
| `NoopMemoryEmbedder` | 单例 | 永远返回 `null`，检索走文本包含匹配 |
| `MemorySummarizer` | `@FunctionalInterface` | `String summarize(List<ChatMessage> messages)`，把对话压缩为摘要 |
| `LengthBasedSummarizer` | 实现 | 按总字符数截断（`DEFAULT_MAX_CHARS=500`），保留首尾两段；零依赖确定性降级 |
| `MemoryExtractor` | `@FunctionalInterface` | `List<MemoryEntry> extract(List<ChatMessage> messages, String sessionId)`，决定沉淀什么 |
| `DefaultMemoryExtractor` | 实现 | 跳过 system/tool；长度 ≥ `DEFAULT_MIN_CONTENT_LENGTH=3` 的 USER →「用户说：…」；最后一条 ASSISTANT →「助手答：…」 |

`MemoryEntry` 是不可变 record：`id / content / metadata(Map) / createdAtEpochMs / embedding(float[])`；
静态工厂 `MemoryEntry.of(content, metadata)` 自动生成 UUID 与时间戳，`withEmbedding(float[])` 返回带向量副本。

### 召回注入与沉淀策略

- **召回注入**：`run(userMessage)` 时，若 `longTermMemory` 存在且用户消息非空，按 `recall(userMessage, 3)` 取 Top-K，
  拼成一条 system 消息 `相关长期记忆：\n- …` 注入到 `baseRequest` 模板消息之后、会话记忆与当轮用户消息之前（不覆盖原 system prompt）。
- **沉淀策略**：run 结束后把当轮用户消息 + 最终答案交给 `remember(turn, sessionId)`；
  `DefaultMemoryExtractor` 只沉淀长用户指令（≥3 字符）与最后一条助手答案，跳过 system / tool 消息。

### 向量检索与文本降级

`recall(query, k)` 的检索路径按顺序：

1. 用 embedder 把查询转向量；若拿到向量且 store 是 `VectorMemoryStore` → `search(vec, k)` 余弦相似度；
2. 否则若 store 是 `VectorMemoryStore` → `searchByText(query, k)` 子串匹配；
3. 纯键值 `MemoryStore`（不支持检索）→ 退化为返回最近 `k` 条。

`NoopMemoryEmbedder`（默认）下全程走文本包含匹配；接入真实 Embedding 模型后自动升级为向量相似度。
数据量大时应把 `InMemoryMemoryStore` 替换为外部向量库实现（实现 `VectorMemoryStore` 即可）。

---

## 5. 四策略组合使用

`ReActAgent` 的 11 参全参构造器可同时注入检查点 + 审批 + 长期记忆，配合 `StreamingAgentListener` 把事件流推到 SSE：

```java
import com.sure.ai.agent.approval.ApprovalGate;
import com.sure.ai.agent.approval.HighRiskApprovalPolicy;
import com.sure.ai.agent.approval.ConsoleApprovalHandler;
import com.sure.ai.agent.approval.TimeoutApprovalHandler;
import com.sure.ai.agent.checkpoint.CheckpointStore;
import com.sure.ai.agent.checkpoint.FileCheckpointStore;
import com.sure.ai.agent.event.AgentEventPublisher;
import com.sure.ai.agent.event.AgentEventSseWriter;
import com.sure.ai.agent.event.StreamingAgentListener;
import com.sure.ai.agent.memory.longterm.LongTermMemory;
import java.time.Duration;

// 检查点：落盘到目录
CheckpointStore checkpointStore = new FileCheckpointStore("/var/sureai/checkpoints");

// 长期记忆：全默认实现
LongTermMemory ltm = new LongTermMemory(null, null, null, null);

// HITL：高危工具（转账/删除等）需人工审批，30s 超时
ApprovalGate gate = new ApprovalGate(
        new HighRiskApprovalPolicy(),
        new TimeoutApprovalHandler(new ConsoleApprovalHandler(), Duration.ofSeconds(30)));

// 流式事件：广播器订阅后逐帧写 SSE
AgentEventPublisher publisher = new AgentEventPublisher();
publisher.subscribe(event -> {
    sseOut.print(AgentEventSseWriter.toSse(event));
});
AgentListener listener = new StreamingAgentListener("agent-1", publisher);

// 11 参构造器：一次性注入全部可选能力（不需要的传 null）
ReActAgent agent = new ReActAgent(client, baseRequest, registry,
        listener,
        ReActAgent.DEFAULT_MAX_ITERATIONS, ReActAgent.DEFAULT_TIMEOUT,
        /*memory*/ null,
        /*checkpointStore*/ checkpointStore, /*sessionId*/ "sess-001",
        /*approvalGate*/ gate,
        /*longTermMemory*/ ltm);

String answer = agent.run("帮我把订单 A100 转账 100 元，并记住我的收货地址");
sseOut.print(AgentEventSseWriter.doneMarker());
```

构造器重载链（越靠后参数越多，其余均委托至 11 参版本）：

| 构造器参数个数 | 注入的可选能力 |
|---|---|
| 3 参 | 基础 `(client, baseRequest, registry)` |
| 6 参 | + `listener, maxIterations, timeout` |
| 7 参 | + `ConversationMemory` |
| 9 参 | + `CheckpointStore, sessionId` |
| 11 参 | + `ApprovalGate, LongTermMemory`（本版本新增） |

> 所有「可选」能力传 `null` 即关闭；关闭后行为与历史版本逐字节一致。
