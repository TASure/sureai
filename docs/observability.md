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

## OpenTelemetry GenAI 桥接（可选模块）

`sure-ai-otel` 把 sureai 的三类横切回调桥接为 **OpenTelemetry GenAI 语义约定**指标，供 OTLP / Jaeger / Tempo / Langfuse 等后端采集。模块**仅依赖 OpenTelemetry API（`provided`，不传递）**，SDK 与导出器由使用方在 runtime 自行引入。

> **无感降级**：传入 `null`（或 `MeterProvider.noop()`）时，桥接实现全部为空操作、零开销；未在 runtime 引入 OpenTelemetry SDK 时本模块类不会被加载，现有行为完全不变。

### 接入三步

```java
// 1) 应用侧自行构建 OpenTelemetry SDK（runtime 引入 opentelemetry-sdk + 导出器）
OpenTelemetry otel = OpenTelemetrySdk.builder()...build();
MeterProvider mp = otel.getMeterProvider();

// 2) 构造 sureai client 时挂载桥接（com.sure.ai.otel.OtelSupport）
AiConfig config = AiConfig.builder()
    .apiKey(key)
    .metricsCollector(OtelSupport.metricsCollector(mp, "openai", "chat"))
    .retryListener(OtelSupport.retryListener(mp, "openai", "chat"))
    .build();

// 3) Agent 事件桥接为 span 事件（订阅事件广播器）
publisher.subscribe(OtelSupport.agentEventSink());
```

### 静态入口 `OtelSupport`

`com.sure.ai.otel.OtelSupport` 是对齐 sureai Util 门面范式的静态入口：

| 方法 | 返回 | 说明 |
|------|------|------|
| `metricsCollector(MeterProvider)` | `MetricsCollector` | 默认操作名 `chat` |
| `metricsCollector(MeterProvider, providerName, operationName)` | `MetricsCollector` | 自定义 `gen_ai.provider.name` / `gen_ai.operation.name` |
| `retryListener(MeterProvider)` | `RetryListener` | 默认操作名 `chat` |
| `retryListener(MeterProvider, providerName, operationName)` | `RetryListener` | 自定义操作名 |
| `agentEventSink()` | `AgentEventSink` | 把 `AgentEvent` 写为当前 span 事件 |

### 映射的 GenAI 语义约定指标

指标名 / 单位逐字取自 [opentelemetry/semantic-conventions-genai](https://github.com/open-telemetry/semantic-conventions-genai)，仪器作用域名（Instrumentation Scope）为 `com.sure.ai.otel`：

| OTel 指标 | 类型 / 单位 | 来源 |
|-----------|-------------|------|
| `gen_ai.client.operation.duration` | DoubleHistogram / `s` | `onRequestSuccess` / `onRequestFailure` 记录一次操作耗时（ms→s）；失败附 `error.type` |
| `gen_ai.client.inference.usage.input_tokens` | LongCounter / `{token}` | `onTokenUsage` 的 prompt tokens |
| `gen_ai.client.inference.usage.output_tokens` | LongCounter / `{token}` | `onTokenUsage` 的 completion tokens |

公共属性：`gen_ai.operation.name`（默认 `chat`，可按 `embeddings` 等配置）、`gen_ai.provider.name`（如 `openai`，留空则不写）、`gen_ai.request.model`、`gen_ai.token.modality=text`；`error.type` 取异常类简单名或 HTTP 状态码字符串（低基数）。

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-otel</artifactId>
    <version>1.4.0</version>
</dependency>
```

## Langfuse 原生导出（v2.4.0）

除 OTel 桥接外，`sure-ai-otel` 的 `com.sure.ai.otel.langfuse` 子包提供**直连 Langfuse Ingestion API**
的零依赖导出器：把一次逻辑 LLM 调用映射为一个 `trace` + 一个 `generation` 观察，**不依赖
OpenTelemetry SDK 或 Langfuse 官方 SDK**，用 JDK `HttpClient` 直接 POST 到 Langfuse。

### 接入（两种方式）

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.otel.langfuse.LangfuseConfig;
import com.sure.ai.otel.langfuse.LangfuseExporters;

// 方式一：读环境变量（LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY，可选 LANGFUSE_HOST）
AiConfig config = AiConfig.builder()
    .apiKey(key)
    .metricsCollector(LangfuseExporters.metricsCollectorFromEnv())
    .build();

// 方式二：显式配置（自托管实例 / 指定环境标签）
AiConfig config2 = AiConfig.builder()
    .apiKey(key)
    .metricsCollector(LangfuseExporters.metricsCollector(
        LangfuseConfig.of("https://cloud.langfuse.com",   // 基址，空则用默认
            "pk-lf-...", "sk-lf-...", "production")))    // environment 可传 null
    .build();
```

静态门面 `LangfuseExporters`（对齐 `OtelSupport` 范式）：

| 方法 | 返回 | 说明 |
|------|------|------|
| `metricsCollectorFromEnv()` | `MetricsCollector` | 从环境变量构建；未配齐密钥时返回空操作 |
| `metricsCollector(LangfuseConfig)` | `MetricsCollector` | 以显式配置构建；`null` 或禁用态返回空操作 |

`LangfuseConfig` 不可变配置：`of(endpoint, publicKey, secretKey, environment)` / `fromEnv()` /
`disabled()`；`isEnabled()`、`endpoint()`、`publicKey()`、`secretKey()`、`environment()`、
`ingestionUrl()`（= 基址 + `/api/public/ingestion`）。

### 环境变量

| 环境变量 | 兜底（sureai 前缀） | 必填 | 说明 |
|---|---|---|---|
| `LANGFUSE_PUBLIC_KEY` | `SURE_AI_LANGFUSE_PUBLIC_KEY` | ✅ | 项目公钥 `pk-lf-...` |
| `LANGFUSE_SECRET_KEY` | `SURE_AI_LANGFUSE_SECRET_KEY` | ✅ | 项目私钥 `sk-lf-...` |
| `LANGFUSE_HOST` | `SURE_AI_LANGFUSE_ENDPOINT` | ❌ | 实例基址，默认 `https://cloud.langfuse.com` |
| `LANGFUSE_ENVIRONMENT` | （无兜底） | ❌ | 环境标签（如 `production`） |

### 认证与事件映射

- **认证**：HTTP Basic Auth，`Authorization: Basic base64(publicKey:secretKey)`（对齐 Langfuse
  Ingestion API 约定），由 `LangfuseConfig.basicAuthorization()` 生成。
- **一次调用 = 一个 trace + 一个 generation**（`LangfuseMetricsCollector implements MetricsCollector`）：

| MetricsCollector 回调 | Langfuse Ingestion 事件 |
|---|---|
| `onRequestStart` | 新建 `trace-create` + `generation-create`（含 `startTime`） |
| `onTokenUsage` | 暂存 model 与 prompt/completion/total tokens |
| `onRetry` | 追加 `event-create`（name=`retry`，metadata 含 attempt/httpStatus） |
| `onRequestSuccess` | 追加 `generation-update`（`level=DEFAULT`、`endTime`、`model`、`usage`），整批 POST |
| `onRequestFailure` | 追加 `generation-update`（`level=ERROR`、`statusMessage`），整批 POST |

同一逻辑调用的所有事件组装为一个 `batch` 数组、单次 POST 到 `/api/public/ingestion`；
start 与终态由重试模板保证成对，中间态用 `ThreadLocal` 串联，终态一定 `remove()` 防止线程池复用泄漏。

### 无感降级

- `publicKey` / `secretKey` 任一为空 → `LangfuseConfig.isEnabled()==false`，导出器全部空操作，
  主流程零感知、零网络。
- 发送失败由 `LangfuseIngestionClient` 记录 warning 并吞咽，`LangfuseMetricsCollector` 自身任何
  异常也向上不外抛——观测通道故障绝不影响模型主调用。

> 可运行示例：`sure-ai-examples` 的 `com.sure.ai.examples.LangfuseObservationDemo`
> （用 `DeepSeekClient` 演示，未配 Langfuse 密钥时自动跳过）。接入场景见
> [docs/COOKBOOK.md](COOKBOOK.md) 场景 16。

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
