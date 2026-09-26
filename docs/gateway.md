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
