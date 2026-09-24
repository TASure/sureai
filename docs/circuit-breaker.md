# 熔断器 Circuit Breaker

> 包：`com.sure.ai.client.resilience` ｜ 模块：`sure-ai-core`

## 架构

熔断器是跨请求的状态机，与重试（单次请求内退避）、限流（请求前令牌桶）形成三层防护：

```
请求 → [限流 acquire] → [熔断 allowRequest] → [重试 executeWithRetry] → HTTP
         ↑                    ↑                        ↑
       令牌桶              三态状态机              429/5xx 指数退避
```

三者**嵌套**而非互斥：熔断在最外层，OPEN 时直接快速失败，不触发限流/重试/网络/指标。

## 状态机

```
CLOSED ──失败达阈值──→ OPEN ──openTimeout超时──→ HALF_OPEN
  ↑                        ↑                        │
  │                        └──探测失败──────────────┘
  └────────────探测成功（全部）─────────────────────┘
```

| 状态 | 行为 |
|---|---|
| **CLOSED** | 正常放行，记录成功/失败到滑动窗口；失败数 ≥ threshold → OPEN |
| **OPEN** | `allowRequest()` 返回 false，抛 `AiException("Circuit breaker is OPEN")`；超时后转 HALF_OPEN |
| **HALF_OPEN** | 允许 `halfOpenPermittedCalls` 个探测请求；全部成功 → CLOSED；任一失败 → 回 OPEN |

## 配置

```java
CircuitBreaker cb = CircuitBreaker.builder()
    .failureThreshold(3)       // 滑动窗口内失败次数阈值（默认3）
    .windowSize(5)             // 滑动窗口大小（默认5）
    .openTimeoutMs(10_000)     // OPEN 停留时长（默认10s）
    .halfOpenPermittedCalls(1) // HALF_OPEN 探测请求数（默认1）
    .build();

AiConfig config = AiConfig.builder()
    .apiKey("...")
    .circuitBreaker(cb)
    .build();
```

不配置 `circuitBreaker`（默认 null）时零开销，行为与无熔断完全一致。

## 查询状态

```java
CircuitBreaker.Snapshot snap = cb.snapshot();
snap.state();           // CLOSED / OPEN / HALF_OPEN
snap.failureCount();    // 当前滑动窗口失败数
snap.successCount();    // 当前滑动窗口成功数
snap.totalCalls();      // 总请求数
snap.openedAt();        // OPEN 开始时间戳（-1 表示未 OPEN）
```

## 与重试的关系

| 维度 | 重试 Retry | 熔断 Circuit Breaker |
|---|---|---|
| 范围 | 单次请求内部 | 跨请求状态机 |
| 触发 | 429 / 5xx / IO异常 | 滑动窗口失败比例 |
| 行为 | 退避后重发同一请求 | OPEN 时拒绝发请求 |
| 嵌套 | 内层 | 外层（包裹重试） |

OPEN 时不发网络、不触发重试、不触发 MetricsCollector、不触发限流。

## 线程安全

`CircuitBreaker` 使用 `ReentrantLock` 保护状态变更，`Snapshot` 为不可变 record，可在多线程环境安全共享。

## 测试

- `CircuitBreakerTest`：9 个单元测试（状态转换、超时、快照）
- `CircuitBreakerIntegrationTest`：3 个集成测试（本地 HttpServer mock，验证 OPEN 时 0 网络请求、HALF_OPEN 探测成功/失败）
