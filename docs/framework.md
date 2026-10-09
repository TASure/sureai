# 声明式编排（AiService / Advisor 链 / 语义缓存）

sureai 在 `sure-ai-core` 的 `AiClient` 对话原语之上，于 **v2.5.0** 新增 `sure-ai-framework` 声明式编排层：
**把一个带注解的 Java 接口，经 JDK 动态代理直接变成可调用的 AI 服务**，并用有序 **Advisor 链**横切
请求 / 响应（缓存短路、日志、工具循环、结构化自纠）。定位对标 LangChain4j 的 `AiServices` 与
Spring AI 的 Advisor 链——补齐 sureai 此前只有「手写 `client.chat(request)`」而无「接口即服务」的编排短板。

本模块**仅依赖 `sure-ai-core`，零第三方运行期依赖**；接口包 `com.sure.ai.framework`。
英文镜像：[framework.md (English)](en/framework.md)。

---

## 1. 概述

```
@AiService 接口（带注解）
        │  FrameworkUtil.create / builder
        ▼
   JDK 动态代理 FrameworkProxy
        │  解析注解 → 渲染模板 → 组装 ChatRequest
        ▼
   AdvisorChain（正序 before → 嵌套 around → 逆序 after）
        │  命中即短路（语义缓存）/ 日志 / 工具循环 / 结构化自纠
        ▼
   terminal: client.chat(rebuildRequest())   ← 任意平台 AiClient 驱动
```

三层职责：

- **接口层**：注解族（`@AiService` / `@SystemMessage` / `@UserMessage` / `@Tool` / `@Memory` / `@Param`）声明「这个方法怎么变成一次对话」。
- **代理层**：`FrameworkProxy` 把方法签名翻译为 `ChatRequest`，按返回类型（`String` / `ChatResponse` / `Stream` / `record`）映射结果。
- **编排层**：`Advisor` 中间件链，在一次调用路径上横切缓存、日志、工具、校验等横切关注点。

---

## 2. 快速开始

一行创建代理，接口即服务：

```java
import com.sure.ai.client.AiClient;
import com.sure.ai.framework.FrameworkUtil;
import com.sure.ai.framework.annotation.AiService;
import com.sure.ai.framework.annotation.SystemMessage;
import com.sure.ai.framework.annotation.UserMessage;

@AiService(model = "gpt-4o-mini")
interface Assistant {

    @SystemMessage("你是一个{role}助手")
    @UserMessage("请总结：{text}")
    String summarize(String role, String text);
}

// client 是任意平台的 AiClient（OpenAI / DeepSeek / 豆包 …… 均可）
Assistant ai = FrameworkUtil.create(Assistant.class, client);
String result = ai.summarize("法务", "合同正文……");
```

就这么多：注解在接口上，代理在运行时生成，业务代码只面向接口编程。

> 模型名解析优先级：`FrameworkUtil.builder(...).model(x)` 显式设置 > `@AiService(model=...)`；
> 二者皆空时创建代理即抛 `AiException`。

---

## 3. 注解族详解

六个注解，全部 `RUNTIME` 保留：

| 注解 | 作用目标 | 语义 |
|------|---------|------|
| `@AiService` | 接口（TYPE） | 声明默认 `model` 与采样 `temperature`；`temperature` 默认 `-1` 表示不设置 |
| `@SystemMessage` | 方法 | 系统消息模板，渲染后置于消息列表首部 |
| `@UserMessage` | 方法 | 用户消息模板；`value` 默认空串（见下文回退规则） |
| `@Tool` | 方法 | 把方法声明为模型可调用工具（Function），签名自动转 JSON Schema |
| `@Memory` | 接口 / 方法 | 开启会话记忆注入与回写；类级开启全部方法，方法级可覆盖 |
| `@Param` | 参数（PARAMETER） | 显式绑定参数在模板占位符中的名字 |

### 3.1 模板占位符 `{paramName}`

`@SystemMessage` / `@UserMessage` 支持 `{paramName}` 占位符，调用时按**形参名**（或 `@Param` 显式绑定）替换：

```java
@SystemMessage("你是一个{role}助手")
@UserMessage("请回答：{q}")
String chat(String role, String q);
```

- 参数绑定名解析：`@Param("xxx")` 优先；否则回退反射形参名（需编译期保留参数名，即 `javac -parameters`）。
- 未命中的占位符替换为空串。
- **未开启 `-parameters` 时**用 `@Param` 兜底：`String greet(@Param("who") String who)` 可被模板 `"你好，{who}"` 正确替换。

### 3.2 `@UserMessage` 缺省回退规则

- 方法**未标注** `@UserMessage`：仅有一个参数时，该参数的字符串值**直接作为用户消息**；
- 多于一个参数时**必须**标注 `@UserMessage` 模板，否则创建代理时校验失败。

### 3.3 返回类型映射

| 返回类型 | 类别 | 行为 |
|---------|------|------|
| `String` | 阻塞文本 | 返回 `ChatResponse.firstText()` |
| `ChatResponse` | 阻塞原始响应 | 返回完整响应对象 |
| `Stream<ChatStreamChunk>` | 流式 | 逐片收集后返回流（**不经过 Advisor 链**） |
| `record` | 结构化输出 | 自动挂 `responseFormat=json_schema`，返回时反序列化为 record 实例 |
| `void` / 其他 | — | 创建代理期即抛 `AiException` |

```java
interface Examples {
    String echo(String input);                       // 文本
    ChatResponse raw(String input);                  // 原始响应
    Stream<ChatStreamChunk> stream(String input);     // 流式

    record Person(String name, int age) {}            // 结构化
    @UserMessage("生成一个人物")
    Person person(String dummy);
}
```

---

## 4. 工具（@Tool）

把接口方法标为 `@Tool`，代理会在每次请求时自动把它的签名转换为 `ToolSpec`（方法名 / 参数名 → JSON Schema）
挂到 `ChatRequest.tools`：

```java
interface Calc {
    @UserMessage("今天天气如何")
    String ask(String q);

    @Tool(name = "add", description = "计算两数之和")
    default int add(int a, int b) {
        return a + b;
    }
}
```

- `@Tool` 留空 `name` 时使用方法名；`description` 供模型理解何时调用。
- **直调拒绝**：业务代码不能直接调用 `add(...)`——代理对 `@Tool` 方法抛
  `IllegalStateException`（工具方法由模型侧调用）。
- **模型侧触发**：模型在响应里给出 `tool_calls` 后，由 `ToolCallingAdvisor` 闭环执行（见 [§6](#6-advisor-链)），
  经内置 `ReflectionToolExecutor` 反射调用本接口的 `@Tool` 方法并把结果回灌。
- `default` 方法在代理实例上执行；抽象 `@Tool` 方法无方法体，执行结果为错误文本回灌模型。

> `ToolExecutor` 是可插拔 SPI：`String execute(String toolName, String argumentsJson)`。
> 内置 `ReflectionToolExecutor` 按形参名绑定 JSON 参数（支持 `String` / 基本类型及其包装）；
> 高级用户可自实现接入 `sure-ai-agent` 的 `ToolRegistry`，避免 framework → agent 的反向依赖。
> 工具失败一律收敛为错误文本回灌模型，不打断整条链。

---

## 5. 记忆（@Memory + ChatMemory）

注入 `ChatMemory` 并标注 `@Memory` 后，代理自动做多轮上下文管理：

- **请求前**：把历史消息（system 之后、当前 user 之前）注入上下文；
- **阻塞轮结束后**：把本轮 user 消息与助手回复追加回记忆。

```java
import com.sure.ai.framework.FrameworkUtil;
import com.sure.ai.framework.annotation.Memory;
import com.sure.ai.framework.memory.InMemoryChatMemory;

@AiService(model = "gpt-4o-mini")
@Memory                                   // 类级：本接口所有方法默认开启记忆
interface Chatbot {
    String talk(String msg);
}

Chatbot ai = FrameworkUtil.builder(Chatbot.class, client)
        .memory(new InMemoryChatMemory(20))   // 环形窗口：最多保留 20 条
        .build();

ai.talk("我叫小明");
ai.talk("我叫什么名字？");   // 历史已自动注入，模型知道答案
```

- `ChatMemory` 接口：`add(ChatMessage)` / `history()`（时间正序不可变快照）/ `clear()` / `size()`。
- 内置 `InMemoryChatMemory`：`new InMemoryChatMemory()` 默认窗口 20 条；`new InMemoryChatMemory(maxEntries)` 自定义，
  超出按 FIFO 淘汰最早消息；读写 `synchronized`，可多线程共享。
- 流式返回**不回写**记忆（仅阻塞轮回写）。

---

## 6. Advisor 链

Advisor 是一次 AiService 调用路径上的横切中间件，对标 Spring AI 的 `Advisor`。三个钩子：

| 钩子 | 执行顺序 | 语义 |
|------|---------|------|
| `before(AdvisorContext)` | **正序** A→B→C | 在任何模型调用前运行，可改写 `ctx.messages()` |
| `around(AdvisorChain, AdvisorContext)` | **嵌套** A 包 B、B 包 C、C 包终端 | 默认 `chain.proceed(ctx)` 透传；可零次（短路）/一次（改写）/**多次**（工具循环、校验重试） |
| `after(AdvisorContext)` | **逆序** C→B→A | finally 语义，无论终端成功 / 短路 / 异常都会运行 |

`AdvisorContext` 是一次调用的可变上下文：`baseRequest()`（模板字段快照）、`messages()`（可变消息列表，工具回填 /
修正指令直接改这里）、`toolExecutor()`、`expectedType()`（结构化目标 record）、`response()`、
以及 `attribute(key,val)` 挂自定义中间状态；终端真正发请求时用 `rebuildRequest()` 以模板字段 + 当前消息列表重建。

> 链对 advisor 暴露的 `proceed` 视图**不可重排**——它只代表「从当前 advisor 之后到终端」的剩余管线，
> 因此工具循环 / 校验 advisor 可反复 `proceed` 触发多轮模型调用，而不会把外层 `before`/`after` 再跑一遍。

### 6.1 装配顺序建议

按注册顺序传入 `builder.advisors(List.of(...))`。**推荐顺序**：

```
SemanticCacheAdvisor → LoggingAdvisor → ToolCallingAdvisor → StructuredOutputValidationAdvisor
```

- 语义缓存放**最外层**，让短路尽可能早发生；
- 日志放工具循环**外层**，一次编排（含多轮工具调用）只记一条总耗时；
- 工具循环在结构化校验**外层**——先把工具跑完拿到最终答案，再校验结构化形状。

### 6.2 四件套逐个

| Advisor | 构造 | 默认值 | 作用 |
|---------|------|--------|------|
| `SemanticCacheAdvisor` | `(SemanticCache cache)` / `(SemanticCache cache, long ttlMillis)` | TTL 用缓存默认 | 命中即短路、不调模型；miss 后回填缓存 |
| `LoggingAdvisor` | `()`（无参） | — | `java.util.logging` 记录请求概要 / 耗时 / 响应摘要 / 工具数（只摘要，不落全文） |
| `ToolCallingAdvisor` | `()` / `(int maxIterations)` | `maxIterations=5` | 自动工具循环：`tool_calls` → 执行回填 → 再问模型，直到无工具调用或达上限 |
| `StructuredOutputValidationAdvisor` | `()` / `(int maxRetries)` | `maxRetries=2` | 结构化输出校验：不合规则把错误原因作为修正指令回灌，请求模型重写 |

- 四个 Advisor 均**无状态、可并发共享**，每次调用状态存在 `AdvisorContext`。
- `SemanticCacheAdvisor` 以上上下文最后一条 user 文本为 query；query 为空（多模态等）时退化为透传，不读不写缓存。
- `ToolCallingAdvisor` 单工具失败 / 未注册 / 参数解析失败收敛为错误文本回灌，不影响其余工具与主循环；达 `maxIterations` 仍有 `tool_calls` 时原样返回最后响应。
- `StructuredOutputValidationAdvisor` 仅在返回 record（`expectedType()` 非空）时介入；响应带 `tool_calls` 的中间轮跳过校验。

---

## 7. SemanticCache（语义缓存）

core 的精确缓存要求请求逐字段一致；**语义缓存**把 query 文本经 `EmbeddingClient` 编码为向量，
与已存条目逐条算**余弦相似度**，达到阈值且未过期即视为命中——让「同义改写」的不同措辞复用同一条响应。

```java
import com.sure.ai.framework.cache.SemanticCache;

SemanticCache cache = SemanticCache.builder()
        .embedder(embeddingClient)          // 必填：任意 core EmbeddingClient
        .threshold(0.85)                    // 余弦相似度阈值，默认 0.85
        .maxEntries(1000)                   // 内存向量索引容量，默认 1000，超 LRU 淘汰
        .defaultTtlMillis(10 * 60 * 1000)   // 默认 TTL，10 分钟
        .model("text-embedding-v1")         // 传给 embedder 的向量模型名（可选）
        .build();
```

Builder 配置项（`com.sure.ai.framework.cache.Builder`）：

| 方法 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `embedder(EmbeddingClient)` | ✅ | — | 向量客户端，任何 core `EmbeddingClient` 实现均可插拔 |
| `threshold(double)` | | `0.85` | 余弦相似度 ≥ 该值才命中，取值 `[0,1]` |
| `store(CacheStore)` | | 进程内 `LruCacheStore` | 响应载荷持久化后端；传 rag 的 `RedisCacheStore` 即获分布式共享缓存 |
| `maxEntries(int)` | | `1000` | 内存向量索引容量，超出 LRU 淘汰 |
| `defaultTtlMillis(long)` | | `600_000`（10 分钟） | `put` 的 `ttlMillis<=0` 时使用 |
| `model(String)` | | `null` | 传给 `EmbeddingClient` 的模型名 |

**存储模型（双层）**：内存维护一份向量索引（query 原文 + 向量 + 过期时间戳，供相似度顺序扫描），
而 `ChatResponse` 载荷通过可插拔的 core `CacheStore` 持久化。条目以 `sha256(query)` 为 entryId，
既作索引键也作载荷存储键。

> 语义比较在内存顺序线性扫描，仅适合条目受控（千级以下）；大规模场景需外接向量索引，本批不展开。
> 与 Redis 组合：`.store(redisCacheStore)` 即可把载荷落到 Redis 多实例共享（见 [docs/cache.md](cache.md)）。

---

## 8. 完整示例（端到端）

接口 + 工具 + 四件套装配 + 语义缓存：

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

// 目标 record：结构化输出
record Product(String name, int stock) {}

@AiService(model = "gpt-4o-mini")
interface Catalog {

    @UserMessage("{q}")
    Product lookup(String q);

    @Tool(description = "查库存")
    default int stockOf(String item) {
        return queryStockDb(item);          // 你的真实库存查询
    }
}

// 1) 语义缓存
SemanticCache cache = SemanticCache.builder()
        .embedder(embeddingClient)
        .threshold(0.85)
        .build();

// 2) Advisor 链：缓存 → 日志 → 工具循环 → 结构化校验
List<Advisor> advisors = List.of(
        new SemanticCacheAdvisor(cache),
        new LoggingAdvisor(),
        new ToolCallingAdvisor(),
        new StructuredOutputValidationAdvisor());

// 3) 一行装配
Catalog ai = FrameworkUtil.builder(Catalog.class, client)
        .advisors(advisors)
        .build();

// 4) 调用：模型可能自动调 stockOf 工具、校验不达标会自纠重写、命中过的问题走缓存
Product p = ai.lookup("查一下《深入理解计算机系统》的库存");
System.out.println(p.name() + " 库存=" + p.stock());
```

执行轨迹：请求进入链 → `SemanticCacheAdvisor` 先查语义缓存（miss）→ `LoggingAdvisor` 开始计时 →
`ToolCallingAdvisor` 调模型，模型返回 `tool_calls=[stockOf]` → 反射执行并回填 → 再问模型 →
模型返回合规 JSON → `StructuredOutputValidationAdvisor` 校验通过 → 响应回填语义缓存 → 日志记录总耗时。

---

## 9. 与既有能力的关系

- **AiClient**：`sure-ai-framework` 不绑定任何平台，终端就是你传入的任意 `AiClient`；
  换平台只换 client，接口与 Advisor 链原样复用。
- **RAG**：RAG 管线（检索增强）在 `sure-ai-rag`；声明式接口负责「对话编排」，二者可组合——
  在 `@Tool` 方法里调用 RAG 检索器，模型即可按需检索。
- **Agent**：`sure-ai-agent` 的 ReActAgent 是更重的自主规划 / 执行循环；framework 的
  `ToolCallingAdvisor` 是轻量的「单接口工具循环」，二者定位互补，不互相替代。
- **缓存**：core 精确缓存（`ChatCacheKey` / `CacheStore`，见 [docs/cache.md](cache.md)）与本层
  语义缓存互补——前者精确、后者同义命中；`SemanticCache.store(...)` 直接复用 `CacheStore` SPI。
- **结构化输出**：底层仍是 core 的 `responseFormat` 抽象（见 [docs/structured-output.md](structured-output.md)），
  framework 只是把「返回 record」自动翻译成 `json_schema` 约束并加了自纠重试。

---

## 附：相关文档

- 端到端可抄示例：[docs/COOKBOOK.md](COOKBOOK.md) 场景 17
- 精确响应缓存 vs Redis：[docs/cache.md](cache.md)
- 结构化输出底层：[docs/structured-output.md](structured-output.md)
- Agent 自主编排：[docs/agent.md](agent.md)
- 英文镜像：[en/framework.md](en/framework.md)
