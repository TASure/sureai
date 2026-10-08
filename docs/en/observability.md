# Observability & Retry

> English page · Chinese source: [../observability.md](../observability.md).

sureai wraps the whole request → retry → backoff template in `sure-ai-core` and
exposes three optional cross-cutting hooks. When unmounted, behavior is identical to
before and costs nothing.

## Minimal example

```java
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.observability.AiMetrics;

AiMetrics metrics = new AiMetrics();

AiConfig config = AiConfig.builder()
        .apiKey(System.getenv("SURE_AI_OPENAI_API_KEY"))
        .maxRetries(2)
        .rateLimitQps(10)                       // 0 = off
        .metricsCollector(metrics)              // built-in zero-dependency metrics
        .retryListener((attempt, status, ex, backoffMs, path) ->
                System.out.printf("retry #%d on %s status=%d%n", attempt, path, status))
        .build();

// after calls, read a snapshot
AiMetrics.Snapshot s = metrics.snapshot();
System.out.println("requests=" + s.totalRequests()
        + " failure=" + s.failureCount() + " avgMs=" + s.avgDurationMs());
```

## Retry semantics

| Item | Rule |
|---|---|
| Retriable status codes | `429` / `500` / `502` / `503` / `504` |
| Backoff | `Retry-After` header wins; otherwise exponential `1s / 2s / 4s ...` |
| Max retries | `maxRetries` (default 2) — up to that many extra attempts |
| IO / interrupt | **not** retried; mapped to `AiTimeoutException` / `AiException` |

## AiMetrics

Zero-dependency, thread-safe in-memory aggregation: `totalRequests`, `successCount` /
`failureCount`, `retryCount`, per-status-code counts, total/average duration, a
5-bucket duration histogram, and cumulative prompt/completion/total tokens. Use
`snapshot()` for an immutable view and `reset()` to clear.

## Micrometer bridge (optional)

Module `sure-ai-micrometer` provides `MicrometerMetricsAdapter implements MetricsCollector`.
`micrometer-core` is `provided` (not transitive), so the runtime stays zero-dependency:

```java
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
AiConfig config = AiConfig.builder()
        .apiKey(k).baseUrl(u)
        .metricsCollector(new MicrometerMetricsAdapter(registry))
        .build();
```

## OpenTelemetry GenAI bridge (optional)

Module `sure-ai-otel` bridges the callbacks to OpenTelemetry GenAI semantic-convention
metrics, collected by OTLP / Jaeger / Tempo / Langfuse backends:

```java
import com.sure.ai.otel.OtelSupport;

AiConfig config = AiConfig.builder()
        .apiKey(key)
        .metricsCollector(OtelSupport.metricsCollector(mp, "openai", "chat"))
        .retryListener(OtelSupport.retryListener(mp, "openai", "chat"))
        .build();
```

It emits `gen_ai.client.operation.duration`, `gen_ai.client.inference.usage.input_tokens`
and `...output_tokens`. Passing `null` (or `MeterProvider.noop()`) degrades to a no-op
with zero overhead, and the module classes are not even loaded unless the OTel SDK is
present at runtime.

## Next steps

- [Trust & supply chain](./trust.md) · [Gateway](./gateway.md) · [Quick Start](./quickstart.md)
