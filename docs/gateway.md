# AI Gateway（sure-ai-gateway）

对调用方透明的多供应商统一网关。`GatewayClient` 实现 `AiClient` 接口——调用方拿到的就是一个普通 client，无需感知背后注册了哪些平台、用了什么路由策略。

- 模块坐标：`io.github.tasure:sure-ai-gateway`
- 包路径：`com.sure.ai.gateway`
- 核心能力：多供应商注册 · 6 种路由策略 · 自动故障转移 · 密钥池轮转 · 租户配额预算
- 平台隔离：网关模块只依赖 `sure-ai-core` + `sure-ai-cost`，不 import 任何具体平台模块

## 快速上手

```java
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.gateway.FailoverConfig;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatMessage;
import java.util.List;

// 1. 注册多个平台 client（openAiClient / deepSeekClient 由你从各平台模块创建）
ClientRegistry registry = new ClientRegistry();
registry.register("openai", openAiClient);
registry.register("deepseek", deepSeekClient);

// 2. 创建网关（默认组合策略：显式平台 → 能力过滤 → 轮询；默认故障转移配置）
GatewayClient gateway = new GatewayClient(registry);

// 3. 像普通 AiClient 一样调用
ChatResponse resp = gateway.chat(ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("你好")))
    .build());
System.out.println(resp.firstText());
```

## ClientRegistry

多供应商客户端注册表。按 `(platform, instanceId)` 二级存储，支持同平台多实例。

### register() 方法签名

```java
// 最简：默认实例 "default"，默认能力 CHAT/CHAT_STREAM，权重 1.0
registry.register("openai", openAiClient);

// 指定实例 ID
registry.register("openai", "eu", openAiClientEu);

// 指定能力集合
registry.register("openai", openAiClient, Set.of(Capability.CHAT, Capability.CHAT_STREAM, Capability.EMBED));

// 全量：平台 + 实例 + client + 能力 + 权重 + 默认模型
registry.register("openai", "primary", openAiClient,
    Set.of(Capability.CHAT, Capability.CHAT_STREAM),
    2.0,          // 权重（加权路由用，>0）
    "gpt-4o");    // 默认模型名（最低成本路由查价用）
```

> 由于 core 中 `capabilities()` 是 `protected`，网关采用**注册时显式声明能力**的方案。不传入能力时默认声明 `CHAT` + `CHAT_STREAM`。

### 查询方法

```java
List<AiClient> all = registry.all();                        // 所有已注册（含不健康）
List<AiClient> byPlat = registry.byPlatform("openai");      // 某平台全部实例
List<AiClient> byCap = registry.byCapability(Capability.EMBED); // 声明了 EMBED 能力的
AiClient named = registry.byName("openai", "default");      // 精确查找
registry.markUnhealthy("openai", "default", 30_000L);     // 手动标记不健康（冷却 30s）
boolean healthy = registry.isHealthy("openai", "default"); // 查健康状态
```

## 路由策略

策略可通过装饰器模式组合：过滤器型策略先收窄候选列表，再委托给叶子型策略做最终选择。

### 6 种策略

| 策略 | 类型 | 用途 |
|------|------|------|
| `ExplicitRoutingStrategy` | 过滤器 | 调用方在请求中指定了 `extra["platform"]` 或模型名带 `platform:` 前缀时，只在该平台实例中选择 |
| `CapabilityRoutingStrategy` | 过滤器 | 只保留声明了本次调用所需能力（如 `EMBED`）的候选 |
| `RoundRobinStrategy` | 叶子 | 按调用顺序在候选间循环分配（`AtomicInteger` 线程安全） |
| `WeightedRoutingStrategy` | 叶子 | 按注册时声明的权重按概率选择 |
| `LowestLatencyStrategy` | 叶子 | 选择滑动窗口内平均延迟最低的候选 |
| `LowestCostStrategy` | 叶子 | 选择输入单价最低的候选（基于 `PriceCatalog`） |

### 组合模式

默认组合（`GatewayClient(registry)` 构造器自动使用）：

```java
new ExplicitRoutingStrategy(
    new CapabilityRoutingStrategy(
        new RoundRobinStrategy()))
```

自定义组合示例——显式平台 → 能力过滤 → 最低延迟：

```java
import com.sure.ai.gateway.*;

LatencyTracker tracker = new LatencyTracker();
RoutingStrategy strategy = new ExplicitRoutingStrategy(
    new CapabilityRoutingStrategy(
        new LowestLatencyStrategy(tracker)));

GatewayClient gateway = new GatewayClient(registry, strategy, FailoverConfig.defaults());
```

最低成本路由：

```java
import com.sure.ai.cost.PriceCatalog;

RoutingStrategy strategy = new ExplicitRoutingStrategy(
    new CapabilityRoutingStrategy(
        new LowestCostStrategy(PriceCatalog.defaults())));
```

### 显式路由

调用方可在不改变业务请求结构的前提下强制指定平台：

```java
// 方式一：extra["platform"]
ChatRequest req = ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("你好")))
    .extra("platform", "deepseek")   // 强制路由到 deepseek
    .build();

// 方式二：模型名带前缀 "openai:gpt-4o"
ChatRequest req2 = ChatRequest.builder()
    .model("openai:gpt-4o-mini")     // 前缀 openai 即目标平台
    .messages(List.of(ChatMessage.user("你好")))
    .build();
```

## 故障转移

`GatewayClient` 每次调用从注册表取当前**健康**候选，路由策略选出一个并调用。失败时按异常类型决定是否转移。

### FailoverConfig

```java
FailoverConfig config = FailoverConfig.builder()
    .maxAttempts(3)                              // 最大尝试次数（默认 3）
    .unhealthyCooldownMs(30_000L)                // 失败实例摘除冷却 30s（默认 30s）
    .retryable(SomeCustomException.class)        // 额外可转移异常
    .listener(new MyFailoverListener())          // 事件监听
    .build();
```

### 异常分类（默认策略）

| 异常 | 是否转移 | 说明 |
|------|---------|------|
| `AiTimeoutException` | ✅ 转移 | 超时通常是瞬时的 |
| `AiApiException` HTTP ≥ 500 | ✅ 转移 | 服务端错误 |
| `AiApiException` HTTP 4xx（含 401/403/429） | ❌ 不转移 | 换平台通常也复现，直接抛出 |
| 非 `AiException`（如连接层 IOException） | ✅ 转移 | 原始连接错误 |

> 如需把 429 限流也纳入跨平台转移，可 `.retryable(AiRateLimitException.class)`。

### FailoverListener

```java
public interface FailoverListener {
    void onFailover(String fromPlatform, String fromInstanceId,
            String toPlatform, String toInstanceId, Exception cause, int attempt);
    void onExhausted(List<ClientCandidate> tried, Exception lastCause);
}
```

所有回调在调用线程内同步触发；实现应轻量、不抛异常。

## 密钥池轮转

当一个平台有多个 API Key 需要轮询（如多账号分摊限流）时，使用 `ApiKeyProvider` + `KeyRotatingClientDecorator`。

### ApiKeyProvider 接口

```java
String currentKey();                    // 当前应使用的 key
String nextKey();                       // 切到下一个可用 key
void markBad(String key);               // 标记坏 key（冷却期内不再选中）
List<String> allKeys();                 // 全部 key 快照
int size();                             // key 数量
```

### RoundRobinApiKeyProvider

```java
import com.sure.ai.client.RoundRobinApiKeyProvider;

// 默认冷却 60s，系统时钟
ApiKeyProvider provider = new RoundRobinApiKeyProvider(
    List.of("sk-key-1", "sk-key-2", "sk-key-3"));
```

坏 key 通过 `markBad()` 记入冷却表（默认 60s），冷却到期后自动恢复。

### KeyRotatingClientDecorator

包装一个"按 key 构造平台客户端"的工厂，在调用遇到 401/429 时自动换 key 重试。

```java
import com.sure.ai.gateway.KeyRotatingClientDecorator;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.client.ApiKeyProvider;

// 工厂：给定一个 apiKey，构造一个平台客户端
ApiKeyProvider provider = new RoundRobinApiKeyProvider(
    List.of("sk-key-1", "sk-key-2", "sk-key-3"));

KeyRotatingClientDecorator rotating = new KeyRotatingClientDecorator(
    provider,
    apiKey -> OpenAiClient.builder().apiKey(apiKey).build()
);

// 注册进网关——对上层透明
registry.register("openai", rotating);
```

默认在遇到 `AiAuthException`（401/403）或 `AiRateLimitException`（429）时自动换 key 重试。最多尝试 `provider.size()` 次（每个 key 一次）。全部 key 都失败时抛出 `AiException`。

> 自定义换 key 判定：使用带 `Predicate<Throwable>` 的构造器，传入自定义的异常判定逻辑。

## 租户配额与预算

面向多租户场景：调用方持有虚拟密钥，网关解析出租户后做成本/token/QPS 限额管控。

### TenantManager

维护"虚拟密钥 → 租户"映射与"租户 → 配额配置"映射。

```java
import com.sure.ai.gateway.TenantManager;

TenantManager tenantManager = new TenantManager();

// 注册虚拟密钥
tenantManager.registerVirtualKey("vk-tenant-alice", "alice");
tenantManager.registerVirtualKey("vk-tenant-bob", "bob");

// 解析虚拟密钥对应的租户
String tenantId = tenantManager.resolveTenant("vk-tenant-alice");  // "alice"

// 配置租户配额
tenantManager.configureTenant("alice", TenantConfig.builder()
    .maxCostPerPeriod(10.0)         // 周期内成本上限 $10
    .maxTokensPerPeriod(1_000_000)  // 周期内 token 上限 100 万
    .rateLimitQps(5)                // QPS 上限 5
    .budgetPeriod(Duration.ofDays(1)) // 预算周期 1 天（默认）
    .build());
```

任一限额字段为 `0` 表示该维度不限。未配置 `TenantConfig` 的租户视为不限额。

### BudgetEnforcer

调用前做配额/预算/QPS 检查，调用后记录实际成本与 token。

```java
import com.sure.ai.gateway.BudgetEnforcer;
import com.sure.ai.cost.CostAggregator;

CostAggregator aggregator = new CostAggregator();
BudgetEnforcer enforcer = new BudgetEnforcer(tenantManager, aggregator);

// 调用前检查（超限抛 AiBudgetExceededException）
enforcer.checkBeforeCall("alice", estimatedCost);

// 调用成功后记录
enforcer.recordAfterCall("alice", "gpt-4o", usage, actualCost);

// 查询当前用量
TenantUsage usage = enforcer.currentUsage("alice");
System.out.println("已用成本: $" + usage.costUsed() + " / $" + usage.costLimit());
```

预算周期到期后窗口用量自动清零。QPS 复用 sure-core 令牌桶（非阻塞 `tryAcquire`）。

### TenantAwareGatewayClient

装饰一个 `GatewayClient`，在每次调用前后接入 `BudgetEnforcer`。租户 ID 从 `ChatRequest.extra["tenantId"]` 读取。

```java
import com.sure.ai.gateway.TenantAwareGatewayClient;
import com.sure.ai.cost.CostCalculator;
import com.sure.ai.cost.PriceCatalog;

TenantAwareGatewayClient tenantClient = new TenantAwareGatewayClient(
    gateway,
    enforcer,
    new CostCalculator(PriceCatalog.defaults())  // 可选：事后成本折算
);

// 调用时在 extra 中传入租户 ID
ChatRequest req = ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("你好")))
    .extra("tenantId", "alice")
    .build();
ChatResponse resp = tenantClient.chat(req);  // 超限抛 AiBudgetExceededException
```

未带 tenantId 的请求直接透传，不做任何管控。流式调用只做事前检查、不做事后计量。

### AiBudgetExceededException

```java
import com.sure.ai.exception.AiBudgetExceededException;

try {
    tenantClient.chat(req);
} catch (AiBudgetExceededException ex) {
    System.out.println("租户 " + ex.tenantId() + " 超限: " + ex.getMessage());
}
```

## LatencyTracker

记录每个 `(平台, 实例)` 的历史延迟，供 `LowestLatencyStrategy` 使用。滑动窗口：最多 100 条样本、丢弃超过 5 分钟的样本。无历史数据的候选默认 10000ms（略高，降低冷启动被选中概率）。

```java
LatencyTracker tracker = new LatencyTracker();
tracker.record("openai", "default", 250L);  // 记录一次 250ms 的成功调用
double avg = tracker.averageLatency("openai", "default");  // 平均延迟
tracker.reset();  // 清空
```

## 生产化能力（v2.7.0 新增）

在既有「注册 → 路由 → 故障转移 → 租户管控」之上，v2.7.0 补齐网关上线生产环境所需的横切能力：按 client 维度的三态熔断、健康探活、请求级超时、流式故障转移边界、指标与审计、租户并发上限，以及一个对标 `OpenAiUtil` 的静态装配入口 `GatewayUtil`。全部为可选装配——不注入这些组件时行为与既有版本完全一致。

### 熔断器

网关按 `(platform, instanceId)` 维度懒创建 core 的三态熔断器（CLOSED → OPEN → HALF_OPEN），直接复用 `com.sure.ai.client.resilience.CircuitBreaker`，本模块只做维度持有与配置下发。

**CircuitBreakerConfig 配置参数**（不可变 record，`CircuitBreakerConfig.defaults()` / `builder()`）：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `failureThreshold` | int | 3 | 滑动窗口内失败多少次即转 OPEN（≥1） |
| `windowSize` | int | 5 | 滑动窗口大小（最近 N 次请求，≥ failureThreshold） |
| `openTimeoutMs` | long | 10000 | OPEN 状态停留时长（毫秒，>0），到期转 HALF_OPEN |
| `halfOpenPermittedCalls` | int | 1 | HALF_OPEN 允许的探测请求数（≥1） |

```java
import com.sure.ai.gateway.CircuitBreakerConfig;
import com.sure.ai.gateway.CircuitBreakerRegistry;

// 默认配置：3 次失败开窗 / 窗口 5 / 打开 10s / 半开放探测 1
CircuitBreakerRegistry breakers = CircuitBreakerRegistry.defaults();

// 自定义：更敏感（2 次失败即开窗、冷却 30s）
CircuitBreakerRegistry custom = new CircuitBreakerRegistry(
    CircuitBreakerConfig.builder()
        .failureThreshold(2)
        .windowSize(5)
        .openTimeoutMs(30_000L)
        .halfOpenPermittedCalls(1)
        .build());
```

**CircuitBreakerRegistry 关键方法**：`allowRequest(platform, instanceId)`（无实例时懒创建并转发 core `allowRequest`）、`onSuccess(...)` / `onFailure(...)`（懒创建）、`snapshot(platform, instanceId)`（未创建过返回 `null`）、`snapshots()`（全部已创建熔断器快照，key 为 `platform|instanceId`，不可变副本）、`size()`、`reset()`。

**路由过滤语义**：`GatewayClient` 每次取候选时，若装配了熔断器注册表，会把处于 OPEN 的候选从本次可选集合中**剔除**——被剔除者**不发起真实调用**，计入本次 tried 列表并触发 `GatewayMetrics.onCircuitReject(...)`（熔断快速失败拒绝计数 +1）。OPEN 期间的拒绝本身是瞬时的，被剔除的候选会像其它失败一样进入 failover 链的下一个候选。

**全熔断快速失败**：当某平台（或本次能力过滤后）所有候选的熔断器都处于 OPEN 时，不再有可调用对象，`GatewayClient` 直接抛出快速失败异常，消息含 `circuit breaker` 字样（`gateway fast-fail: all circuit breakers OPEN after N attempt(s)`），调用方据此区分「全部熔断」与「真实调用失败耗尽」。

> 熔断器与注册表健康摘除（`markUnhealthy` 冷却）是两套独立机制：熔断由**调用结果**驱动（失败计数到阈值开窗），健康摘除由**探活 / 调用失败冷却**驱动；两者在路由候选过滤时叠加生效。

### 健康探活

**HealthProbe SPI**（`boolean isHealthy(AiClient client, String platform, String instanceId)`）：实现须轻量快速、不可阻塞太久；运行时异常由 `HealthMonitor` 捕获并按不健康处理。内置 `DefaultHealthProbe` 恒返回 `true`——即**默认不额外探活**，健康判定完全交给熔断器与调用失败摘除；生产环境可注入自定义实现（如真实 ping / 轻量探测请求）。

**HealthMonitor**：定期对注册表候选做探活并同步健康状态。

- 构造：`new HealthMonitor(registry, probe)`（默认不健康冷却 30s、探活间隔 15s，自建单线程 daemon 调度器）；或全参 `new HealthMonitor(registry, probe, unhealthyCooldownMs, intervalMs)`。
- `start()` 幂等（重复调用不产生第二个调度）、`stop()` 幂等（`shutdownNow`）。
- `runOnce()` 同步执行一轮：遍历 `registry.candidates()`，探活成功 → `markHealthy`，探活返回 `false` 或抛异常 → `markUnhealthy(冷却 30s)`；同时记录探活结果与时间戳。
- `healthView()` 汇总所有候选的 `ClientHealthView`（含熔断状态，见下文）。

**ClientHealthView 字段表**（不可变 record）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `platform` | String | 平台名 |
| `instanceId` | String | 实例 ID |
| `healthy` | boolean | 当前是否在路由健康集合内（综合冷却期判断） |
| `circuitState` | `CircuitBreaker.State` | 熔断器状态；`null` 表示该 client 未配置 / 未创建熔断 |
| `probeHealthy` | Boolean | 最近一次探活结果；`null` 表示从未探活 |
| `lastProbeAtMs` | long | 最近一次探活时间戳（毫秒 epoch），0 表示从未探活 |

> **两套 healthView 的分工**：`GatewayClient.healthView()` 基于注册表枚举全部实例，`circuitState` 取熔断快照，但**不含探活维度**（`probeHealthy` 恒为 `null`、`lastProbeAtMs` 恒为 0）；探活结果由 `HealthMonitor.healthView()` 负责汇总。`HealthMonitor` 装配熔断器注册表后，其 healthView 会把熔断状态一并带出（两者可组合使用）。

### 请求超时

`GatewayTimeoutDecorator` 把被装饰 `AiClient` 的每次调用放到懒创建的单线程 daemon 执行器（线程名 `gateway-timeout`）上，调用方通过 `future.get(timeout, MILLISECONDS)` 限时等待。

```java
import com.sure.ai.gateway.GatewayTimeoutDecorator;

// 注册时把超时装饰器包在真实 client 外（对上层透明）
registry.register("openai", new GatewayTimeoutDecorator(openAiClient, 30_000));
registry.register("deepseek", new GatewayTimeoutDecorator(deepSeekClient, 60_000));
```

- **per-request 覆盖**：请求 `extra` 中键 `GatewayTimeoutDecorator.EXTRA_TIMEOUT_MS`（即 `"timeoutMs"`）为正 `Number` 时，本次调用以该值（毫秒）为准；缺失 / 非法（null / 非 Number / 非正数）一律回退构造器默认值，不抛异常。

```java
ChatRequest req = ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("你好")))
    .extra("timeoutMs", 10_000)   // 本次调用 10s 超时，覆盖注册时的 30s
    .build();
```

- **与 failover 联动**：超时统一抛 `AiTimeoutException`，而 `FailoverConfig` 默认将其视为**可转移异常**——因此装饰器包在网关故障转移链内时，一次超时会自动触发切换到下一个候选，对上层透明。
- **流式超时**：整个流式调用（含全部分片投递）超过 timeout 即抛 `AiTimeoutException`；实现上用一个 volatile 标志让包装 consumer **丢弃后续迟到分片**（worker 线程可能仍在投递），再向调用方抛出超时异常。
- **delegate 异常原样透传**：被装饰 client 抛出的业务异常解包后保持原类型与实例抛出，以便 `FailoverConfig` 按类型判定是否可转移。
- **close()**：幂等——先 `close()` delegate，再 `shutdownNow()` 自己的执行器。

### 流式故障转移

`GatewayClient.chatStream` 使用独立模板 `executeStream`，其故障转移边界以**第一片是否已投递**为界：

| 阶段 | 下游异常行为 |
|------|-------------|
| 第一片投递**之前**抛异常 | 正常故障转移（与同步 `execute` 相同的熔断过滤 / 计数 / 审计钩子），切换下一候选重试 |
| 第一片**已投递**之后再抛异常 | 原样抛出，**不转移**——避免向调用方重复投递已经发出的内容 |

> 流式路径成本恒记 0（分片不带累计用量，与 `TenantAwareGatewayClient` 流式只做事前检查、不做事后计量的口径一致）。

### 观测与审计

**GatewayMetrics**：面向网关层事件（请求计数、路由成败、熔断、成本估算）的进程内聚合器，与 core 的 `com.sure.ai.client.observability.AiMetrics` / `MetricsCollector` 体系**同构**（零第三方依赖、进程内、线程安全、`snapshot()` 返回不可变快照、`reset()` 清零）；区别在于本类面向网关事件而非 HTTP 路径回调，生产环境可由 `sure-ai-micrometer` 模块把快照桥接到 Micrometer。

埋点方法：`onRequestStart(method, model)` / `onRequestSuccess(method, model, latencyMs)` / `onRequestFailure(method, model, latencyMs)`（失败也计入延迟分布）/ `onCircuitTrip(platform, instanceId)`（CLOSED→OPEN）/ `onCircuitReject(platform, instanceId)`（OPEN 期拒绝）/ `recordCost(costUsd)`（累加）。延迟样本有界（最近 4096 条，超出淘汰最旧），快照时在样本副本上排序计算百分位。

**Snapshot 字段表**（`GatewayMetrics.Snapshot`，不可变 record）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalRequests` | long | 请求总数 |
| `successCount` | long | 成功数 |
| `failureCount` | long | 失败数 |
| `circuitBreakerTrips` | long | 熔断触发次数（CLOSED→OPEN） |
| `circuitBreakerRejections` | long | 熔断快速失败拒绝次数（OPEN 状态下） |
| `avgLatencyMs` | double | 平均延迟（ms），无样本为 0 |
| `p50Ms` | double | p50 延迟（ms） |
| `p95Ms` | double | p95 延迟（ms） |
| `p99Ms` | double | p99 延迟（ms） |
| `estimatedCostUsd` | double | 累计估算成本（USD） |

**AuditEvent + GatewayAuditLogger**：审计只落**一行概要**、不落全文（不含消息体 / 完整响应）。`AuditEvent` 字段：`requestId` / `method` / `model` / `tenantId` / `platform` / `instanceId` / `status` / `durationMs` / `estimatedCostUsd` / `detail` / `timestampMs`。`status` 枚举：`SUCCESS` / `FAILED` / `FAILED_OVER`（预算 / 限额超限）/ `REJECTED`（熔断快速失败、无可用实例）/ `TIMEOUT`。

`GatewayAuditLogger` 基于 JUL（默认 logger 名 `com.sure.ai.gateway.Audit`，级别 INFO），`log(event)` / `log(List<event>)`；`detail` 截断到 200 字符。一行输出为竖线分隔的概要：

```
requestId|method|tenantId|platform|instanceId|STATUS|durationMs|estimatedCostUsd|detail
```

### 租户并发限流

在既有 `TenantConfig`（成本 / token / QPS / 预算周期）之上新增第 5 个组件 **`maxConcurrency`**（并发请求上限，0 = 不限），配套 `limitedConcurrency()`。

```java
tenantManager.configureTenant("alice", TenantConfig.builder()
    .maxCostPerPeriod(10.0)
    .maxTokensPerPeriod(1_000_000)
    .rateLimitQps(5)
    .maxConcurrency(20)              // 新增：alice 同时最多 20 个在途请求
    .budgetPeriod(Duration.ofDays(1))
    .build());
```

- **BudgetEnforcer 并发闸门**：`tryAcquireConcurrency(tenantId)` 非阻塞获取许可——tenantId 空白、租户未配置或 `maxConcurrency<=0`（不限）时恒返回 `true` 且不建闸门；已达上限返回 `false`（**不抛异常**，由调用方决定如何拒绝）。实现为每租户**公平 `Semaphore`**、按租户懒加载；`releaseConcurrency(tenantId)` 对称释放。
- **配对契约**：只能对 `tryAcquireConcurrency` 返回 `true` 的请求调用 `releaseConcurrency`；不限 / 未配置 / 从未获取过许可的租户调用释放为 no-op，不校验配对、不抛异常。同租户改配 `maxConcurrency` 后下次准入按新上限重建闸门。
- **TenantAwareGatewayClient 自动配对**：取到 tenantId 后先 `checkBeforeCall`（成本 / token / QPS），再 `tryAcquireConcurrency` 获取许可（已满抛 `AiBudgetExceededException`，不触达下游），调用下游，`finally` 中 `releaseConcurrency` 对称释放——**chat 与 chatStream 均在 finally 释放**，即使下游抛异常也释放。
- **QPS 复用 core 令牌桶**：`rateLimitQps` 维度仍走 core `RateLimiter` 非阻塞 `tryAcquire`（`checkBeforeCall` 既有逻辑），与并发闸门相互独立、互不影响；预算周期到期后窗口成本 / token 用量清零，但令牌桶按秒平滑补充、不随窗口重置。

### 快速上手（GatewayUtil）

`GatewayUtil` 是对标 `OpenAiUtil` 的静态装配入口（final 类 + `SingletonHolder` 懒加载单例），一行拿齐「注册表 + 路由 + 故障转移 + 熔断 + 指标 + 审计 + 成本」的默认装配。

```java
import com.sure.ai.gateway.GatewayUtil;
import com.sure.ai.gateway.GatewayTimeoutDecorator;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatMessage;
import java.util.List;

// 1. Builder 装配（未覆写的字段走默认值）
GatewayUtil util = GatewayUtil.builder()
    // 默认策略：Explicit → Capability → RoundRobin；默认 FailoverConfig.defaults()
    // 默认熔断 CircuitBreakerRegistry.defaults()；默认 new GatewayMetrics() + new GatewayAuditLogger()
    // 默认成本 new CostCalculator(PriceCatalog.defaults())
    .healthProbe(new DefaultHealthProbe())   // 可选：装配健康探活（构建后处于停止态）
    .healthIntervalMs(15_000L)
    .build();

// 2. 注册下游 client（超时装饰器包在真实 client 外）
util.registry().register("openai",
    new GatewayTimeoutDecorator(openAiClient, 30_000));
util.registry().register("deepseek",
    new GatewayTimeoutDecorator(deepSeekClient, 60_000));

// 3. 像普通 AiClient 一样调用
String reply = util.gateway().chat(ChatRequest.builder()
    .model("gpt-4o-mini")
    .messages(List.of(ChatMessage.user("你好")))
    .build())
    .firstText();

// 4. 打印指标快照
GatewayMetrics.Snapshot s = util.metrics().snapshot();
System.out.printf("requests=%d success=%d fail=%d trips=%d rejects=%d%n",
    s.totalRequests(), s.successCount(), s.failureCount(),
    s.circuitBreakerTrips(), s.circuitBreakerRejections());
System.out.printf("latency avg=%.0f p50=%.0f p95=%.0f p99=%.0fms cost=$%.6f%n",
    s.avgLatencyMs(), s.p50Ms(), s.p95Ms(), s.p99Ms(), s.estimatedCostUsd());

// 5. 可选：启动 / 停止定期探活（幂等；未装配探活时无操作）
util.startHealthMonitor();
// ... 运行期 ...
util.stopHealthMonitor();
```

**单例用法**（对标各平台 `XxxUtil.getInstance()`）：

```java
// 未 init 时按默认装配懒加载单例
GatewayUtil util = GatewayUtil.getInstance();
util.registry().register("openai", new GatewayTimeoutDecorator(openAiClient, 30_000));
util.gateway().chat(req);

// 显式替换单例（自定义装配后注入）
GatewayUtil.init(customUtil);

// 置空单例，下次 getInstance() 重新走默认装配（测试与运行时重配）
GatewayUtil.reset();
```

Builder 可覆写项：`registry` / `strategy` / `failoverConfig` / `circuitBreakers`（自定义 `CircuitBreakerRegistry`）/ `circuitBreakerConfig`（便捷熔断配置，内部包成 Registry）/ `metrics` / `auditLogger` / `costCalculator` / `tenant(tenantManager, costAggregator, enforcer)`（装配后 `gateway()` 返回 `TenantAwareGatewayClient` 包裹）/ `healthProbe` + `healthIntervalMs`。

### 健康视图示例

`GatewayClient.healthView()` 与 `HealthMonitor.healthView()` 都返回 `List<ClientHealthView>`，可直接打印每个实例的综合状态：

```java
for (ClientHealthView v : util.gateway().healthView()) {
    System.out.printf("%s/%s healthy=%b circuit=%s probe=%s lastProbeAt=%d%n",
        v.platform(), v.instanceId(), v.healthy(),
        v.circuitState(), v.probeHealthy(), v.lastProbeAtMs());
}
```

输出形如（`GatewayClient.healthView()` 不含探活维度，故 `probe` 为 `null`、`lastProbeAt` 为 0；`HealthMonitor.healthView()` 在装配熔断注册表后会带出熔断状态与真实探活结果）：

```
openai/default healthy=true circuit=CLOSED probe=null lastProbeAt=0
deepseek/default healthy=false circuit=OPEN probe=false lastProbeAt=1720861200000
```
