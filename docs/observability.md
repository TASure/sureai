# 可观测性与重试（Observability & Retry）

sureai 在 `sure-ai-core` 的 `AbstractAiClient` 层统一封装了「请求 → 重试 → 退避」模板，并对外开放三类横切能力：

- **重试事件回调** `RetryListener`：观测每次重试与重试耗尽。
- **指标埋点** `MetricsCollector` + 内置零依赖实现 `AiMetrics`。
- **客户端限流** `AiConfig.rateLimitQps`（基于 sure-core 令牌桶 `RateLimiter`）。
- **Micrometer 桥接** `sure-ai-micrometer`（可选，`provided` 不传递）。

所有能力均为**可选挂载**：不配置时行为与未引入这些能力前完全一致，零额外开销。

## 快速上手

```java
AiMetrics metrics = new AiMetrics();

AiConfig config = AiConfig.builder()
    .apiKey(System.getenv("SURE_AI_API_KEY"))
    .baseUrl("https://api.openai.com/v1")
    .maxRetries(2)                       // 默认 2
    .rateLimitQps(10)                    // 客户端限流 10 QPS，0=关闭（默认）
    .metricsCollector(metrics)           // 挂载内置指标
    .retryListener((attempt, status, ex, backoffMs, path) ->
        System.out.printf("retry #%d on %s status=%d backoff=%dms%n",
            attempt, path, status, backoffMs))
    .build();

OpenAiCompatClient client = new OpenAiCompatClient(config);
// ... 正常调用 chat / embed ...

AiMetrics.Snapshot s = metrics.snapshot();
System.out.println("requests=" + s.totalRequests()
    + " success=" + s.successCount() + " failure=" + s.failureCount()
    + " retries=" + s.retryCount() + " avgMs=" + s.avgDurationMs());
```

## 重试语义（保持不变）

所有 HTTP 发送路径（`doPostRaw` / `doPostStream` / `doGetRaw` / `doPostBinary` / `doPostMultipart`）共用同一个重试模板，语义与重构前完全一致：

| 项 | 规则 |
|----|------|
| 可重试状态码 | `429` / `500` / `502` / `503` / `504` |
| 退避策略 | `Retry-After` 响应头优先（秒 × 1000ms），否则指数退避 `1s / 2s / 4s ...`（`1000 * 2^attempt`） |
| 最大次数 | `maxRetries`（默认 2），即最多额外重试 2 次 |
| IO / 中断异常 | **不重试**，直接映射为 `AiTimeoutException` / `AiException` 并恢复中断标志 |

重试计数从 0 开始：第 1 次额外请求前退避 `1s`（无 Retry-After 时）。

## RetryListener 重试回调

接口位于 `com.sure.ai.client.observability.RetryListener`，全部方法为 `default` 空实现：

```java
public interface RetryListener {
    // attempt 从 1 开始；IO 异常时 httpStatus=-1、exception 非空
    default void onRetry(int attempt, int httpStatus, Exception exception,
                         long backoffMs, String requestPath) {}
    // 重试耗尽、即将抛错前；attempt 为已执行的重试总次数
    default void onRetryExhausted(int attempt, int httpStatus,
                                  Exception exception, String requestPath) {}
}
```

注册方式：

- `AiConfig.Builder.retryListener(RetryListener)`：追加一个，可多次调用累加。
- `AiConfig.Builder.retryListeners(List<RetryListener>)`：批量追加。
- `AiConfig.retryListeners()`：返回不可变列表（默认空）。

**健壮性**：listener 在请求线程内同步执行；其自身抛出的异常会被 `AbstractAiClient` 捕获并仅记录 warning，绝不影响主请求流程。

## MetricsCollector 指标埋点

接口位于 `com.sure.ai.client.observability.MetricsCollector`，全部方法为 `default` 空实现：

```java
public interface MetricsCollector {
    default void onRequestStart(String path) {}
    default void onRequestSuccess(String path, int httpStatus, long durationMs) {}
    default void onRequestFailure(String path, int httpStatus, Exception exception, long durationMs) {}
    default void onRetry(String path, int attempt, int httpStatus) {}
    default void onTokenUsage(String model, long promptTokens, long completionTokens, long totalTokens) {}
}
```

- 未挂载（默认）：仅一次 null 检查，**零开销**。
- `durationMs` 为整个逻辑请求（含重试与退避）的耗时。
- Token 用量在 chat / embedding 响应解析到 `usage` 时回调。

### AiMetrics 内置实现

`com.sure.ai.client.observability.AiMetrics` 是零依赖、线程安全的进程内聚合实现：

| 指标 | 说明 |
|------|------|
| `totalRequests` | 逻辑请求总数 |
| `successCount` / `failureCount` | 成功 / 失败数 |
| `retryCount` | 重试总次数 |
| `statusCodeCounts` | 各 HTTP 状态码计数（`Map<Integer, AtomicLong>`） |
| `totalDurationMs` / `avgDurationMs` | 累计 / 平均耗时 |
| `durationHistogram` | 五桶：`<100ms / 100-500ms / 500-1000ms / 1-5s / >5s` |
| `totalPromptTokens` / `totalCompletionTokens` / `totalTokens` | Token 累计 |

```java
AiMetrics.Snapshot s = metrics.snapshot(); // 不可变快照，复制当前值
metrics.reset();                          // 清零
```

> 这是零依赖的进程内统计，适合调试与单机观测；生产环境建议接 Micrometer/Prometheus。

## 客户端限流

`AiConfig.Builder.rateLimitQps(double qps)`：

- 默认 `0` = 关闭（不创建限流器，零开销）。
- `>0` 时基于 sure-core `com.sure.tool.thread.RateLimiter`（令牌桶），在**每次 HTTP 发送前**（含重试）阻塞获取令牌。
- `acquire` 被中断时恢复中断标志并抛 `AiException`。

```java
AiConfig.builder().apiKey(k).baseUrl(u).rateLimitQps(10).build(); // 10 QPS
```

## Micrometer 桥接（可选模块）

`sure-ai-micrometer` 提供 `MicrometerMetricsAdapter implements MetricsCollector`：

- `micrometer-core` 以 **`provided`** 引入，**不向使用者传递**，不破坏 core 零运行期依赖特性。
- 使用者自行引入 Micrometer 与 Registry（如 Prometheus）。

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-micrometer</artifactId>
    <version>1.4.0</version>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <version>1.13.6</version>
</dependency>
```

```java
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
AiConfig config = AiConfig.builder()
    .apiKey(k).baseUrl(u)
    .metricsCollector(new MicrometerMetricsAdapter(registry))
    .build();
```

映射关系：

| MetricsCollector | Micrometer |
|------------------|-----------|
| `onRequestSuccess` | counter `sureai.requests.success{path,status}` + timer `sureai.request.duration{path}` |
| `onRequestFailure` | counter `sureai.requests.failure{path,status}` + timer |
| `onRetry` | counter `sureai.requests.retry{path,status}` |
| `onTokenUsage` | counter `sureai.tokens.prompt / completion / total{model}` |

## 完整配置示例

```java
AiMetrics metrics = new AiMetrics();
AiConfig config = AiConfig.builder()
    .apiKey(key)
    .baseUrl(baseUrl)
    .maxRetries(3)
    .rateLimitQps(20)
    .metricsCollector(metrics)
    .retryListener(new RetryListener() {
        @Override
        public void onRetry(int a, int status, Exception ex, long backoffMs, String path) {
            // 告警 / 日志
        }
    })
    .build();
```

## 测试约定

全部基于本地 `com.sun.net.httpserver.HttpServer` mock，零真实网络：

- `RetryListenerTest`：重试回调参数、耗尽回调、listener 异常隔离、无 listener 默认行为、多 listener 累加。
- `AiMetricsTest`：计数、状态码、直方图桶、快照不可变、reset、多线程并发。
- `RateLimitTest`：启用/关闭限流、低 QPS 节流时序。
