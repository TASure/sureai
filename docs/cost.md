# 成本计量（sure-ai-core · com.sure.ai.cost）

价格目录 + 单次成本计算 + 按租户/模型/时间窗汇总，全部纯 JDK 内存实现，零额外依赖。

- 包路径：`com.sure.ai.cost`
- 价格单位：USD / 1K tokens（与厂商公布的 per-1M 价格除以 1000 一致）
- 线程安全：`CostAggregator` 内部使用 `ConcurrentHashMap` + `LongAdder`/`DoubleAdder`，并发 record 不丢数据

## 快速上手

```java
import com.sure.ai.cost.PriceCatalog;
import com.sure.ai.cost.CostCalculator;
import com.sure.ai.model.TokenUsage;

// 内置价格目录（18 个主流模型）
PriceCatalog catalog = PriceCatalog.defaults();

// 单次成本计算
CostCalculator calc = new CostCalculator(catalog);
TokenUsage usage = TokenUsage.of(1500, 300, 1800); // prompt / completion / total
double cost = calc.calculate("gpt-4o", usage);
System.out.printf("本次成本: $%.6f%n", cost);
```

## PriceCatalog

不可变价格目录。`defaults()` 返回内置目录；`withPrice(model, price)` 返回覆盖/新增后的新实例（本实例不变）。

### 内置模型价格表

价格单位均为 **USD / 1K tokens**。核实日期：2026-09-26。

| 模型 | input | output | cacheRead | cacheWrite | 来源 |
|------|-------|--------|-----------|------------|------|
| `gpt-4o` | 0.005 | 0.020 | 0.0025 | 0 | OpenAI pricing |
| `gpt-4o-mini` | 0.00015 | 0.0006 | 0.000075 | 0 | OpenAI pricing |
| `gpt-4.1` | 0.002 | 0.008 | 0.0005 | 0 | OpenAI GPT-4.1 launch |
| `o3` | 0.010 | 0.040 | 0.0025 | 0 | OpenAI o3/o4-mini release |
| `o4-mini` | 0.0011 | 0.0044 | 0.000275 | 0 | OpenAI o3/o4-mini release |
| `claude-opus-4-7` | 0.015 | 0.075 | 0.0015 | 0.01875 | Anthropic pricing |
| `claude-opus-4-6` | 0.015 | 0.075 | 0.0015 | 0.01875 | Anthropic pricing |
| `claude-sonnet-4-6` | 0.003 | 0.015 | 0.0003 | 0.00375 | Anthropic pricing |
| `claude-sonnet-4-5` | 0.003 | 0.015 | 0.0003 | 0.00375 | Anthropic pricing |
| `claude-haiku-4-5` | 0.001 | 0.005 | 0.0001 | 0.00125 | Anthropic pricing |
| `gemini-2.5-pro` | 0.00125 | 0.010 | 0.0003125 | 0 | Google Gemini pricing |
| `gemini-2.5-flash` | 0.0003 | 0.0025 | 0.00003 | 0 | Google Gemini pricing |
| `deepseek-chat` | 0.00028 | 0.00042 | 0.000028 | 0 | DeepSeek pricing |
| `deepseek-reasoner` | 0.00028 | 0.00042 | 0.000028 | 0 | DeepSeek pricing |
| `qwen-turbo` | 0.0004 | 0.0012 | 0 | 0 | Alibaba Qwen billing |
| `qwen-plus` | 0.003 | 0.009 | 0 | 0 | Alibaba Qwen billing |
| `qwen-max` | 0.010 | 0.030 | 0 | 0 | Alibaba Qwen billing |
| `mistral-large-latest` | 0.0005 | 0.0015 | 0 | 0 | Mistral pricing |

> 厂商调价后通过 `withPrice()` 覆盖即可。未收录模型 `priceFor()` 返回 `null`，`calculate()` 返回 `0.0`（网关路由场景下不抛异常）。

### 自定义覆盖

```java
import com.sure.ai.cost.PriceCatalog.ModelPrice;

PriceCatalog catalog = PriceCatalog.defaults()
    .withPrice("my-custom-model", new ModelPrice(0.001, 0.004, 0.0, 0.0));

boolean has = catalog.hasPrice("gpt-4o");        // true
ModelPrice p = catalog.priceFor("gpt-4o");       // ModelPrice[inputPer1k=0.005, ...]
int n = catalog.size();                           // 18 + 1 = 19
```

`ModelPrice` 是一个 record：

```java
public record ModelPrice(double inputPer1k, double outputPer1k,
        double cacheReadPer1k, double cacheWritePer1k, String currency)
```

简化构造（默认 USD）：`new ModelPrice(inputPer1k, outputPer1k, cacheReadPer1k, cacheWritePer1k)`。

## CostCalculator

无状态、线程安全。基于 `PriceCatalog` 中各模型的 per-1K tokens 单价，把实际 token 用量折算为 USD。

### calculate() — 按实际用量计费

```java
CostCalculator calc = new CostCalculator(PriceCatalog.defaults());

// 无缓存 token
double cost1 = calc.calculate("gpt-4o", TokenUsage.of(1500, 300, 1800));

// 含缓存 token（cachedRead/cachedWrite 必须是 promptTokens 的子集）
double cost2 = calc.calculate("claude-sonnet-4-6",
    TokenUsage.of(1500, 300, 1800),
    800,   // cachedReadTokens（已含在 promptTokens 内）
    200);  // cachedWriteTokens（已含在 promptTokens 内）
```

计费公式：

```
billablePrompt = promptTokens - cachedReadTokens - cachedWriteTokens
cost = (billablePrompt * inputPer1k
      + cachedReadTokens  * cacheReadPer1k
      + cachedWriteTokens * cacheWritePer1k
      + completionTokens  * outputPer1k) / 1000
```

### estimate() — 请求前粗估

按字符数估算 token（中文字符约 1.5 字符/token，英文及其他字符约 4 字符/token），用于请求前预算判断：

```java
double est = calc.estimate("gpt-4o", "请用一句话介绍Java", 200);
System.out.printf("预估成本: $%.6f%n", est);
```

> 粗估口径：CJK 统一表意文字 + 扩展 A 按中文计，其余按英文计。此为粗略估算，不替代实际计费。

## CostAggregator

线程安全的内存汇总器。支持按租户、按模型、按时间窗聚合。

```java
import com.sure.ai.cost.CostAggregator;
import com.sure.ai.cost.CostSummary;

CostAggregator aggregator = new CostAggregator();

// 记录一次调用
aggregator.record("tenant-alice", "gpt-4o", TokenUsage.of(1500, 300, 1800), 0.0081);

// 按租户全量汇总
CostSummary byTenant = aggregator.tenantSummary("tenant-alice");

// 按模型全量汇总（跨所有租户）
CostSummary byModel = aggregator.modelSummary("gpt-4o");

// 按时间窗汇总（自某时间戳起，跨所有租户和模型）
long oneHourAgo = System.currentTimeMillis() - 3600_000L;
CostSummary recent = aggregator.summarySince(oneHourAgo);

// 清空全部数据
aggregator.reset();
```

### CostSummary 字段

```java
public record CostSummary(
    long totalCalls,              // 总调用次数
    long totalPromptTokens,        // 总输入 token 数
    long totalCompletionTokens,    // 总输出 token 数
    double totalCost,              // 总成本（USD）
    Map<String, Long> tokensByModel,   // model → prompt+completion tokens
    Map<String, Double> costByModel   // model → USD
)
```

空快照应引用 `CostSummary.EMPTY`。

## CostMetricsCollector

把 `MetricsCollector.onTokenUsage` 回调自动接入成本计算与汇总，无需手动在每次调用后 record。

```java
import com.sure.ai.cost.CostMetricsCollector;
import com.sure.ai.cost.PriceCatalog;
import com.sure.ai.cost.CostAggregator;

PriceCatalog catalog = PriceCatalog.defaults();
CostAggregator aggregator = new CostAggregator();
CostMetricsCollector collector = new CostMetricsCollector(catalog, aggregator);

// 请求处理前设置当前租户（Gateway 过滤器在请求入口调用）
CostMetricsCollector.setCurrentTenantId("tenant-alice");
try {
    // ... 调用 chat，MetricsCollector.onTokenUsage 被自动回调 ...
    // collector 自动用 CostCalculator 算出成本并 record 到 aggregator
} finally {
    // 请求结束后清理 ThreadLocal
    CostMetricsCollector.clearCurrentTenantId();
}

// 查询
CostSummary summary = aggregator.tenantSummary("tenant-alice");
System.out.printf("总成本: $%.4f, 总调用: %d%n", summary.totalCost(), summary.totalCalls());
```

未设置 ThreadLocal 租户时默认归入 `CostMetricsCollector.DEFAULT_TENANT`（`"default"`）。

> 流式调用因分片不带累计用量，`onTokenUsage` 只在非流式响应解析到 usage 时回调。
