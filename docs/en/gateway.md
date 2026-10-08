# AI Gateway

> English page · Chinese source: [../gateway.md](../gateway.md). Module: `sure-ai-gateway`.

`GatewayClient` **implements `AiClient`** — callers see an ordinary client and never
know which platforms are registered behind it, nor which routing strategy picks one.

## Minimal example

```java
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import java.util.List;

// openAiClient / deepSeekClient are built from their platform modules
ClientRegistry registry = new ClientRegistry();
registry.register("openai", openAiClient);
registry.register("deepseek", deepSeekClient);

// default strategy chain: explicit platform → capability filter → round-robin
GatewayClient gateway = new GatewayClient(registry);

ChatResponse resp = gateway.chat(ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("Hello")))
        .build());
```

## Routing strategies (composable)

Filters narrow the candidate list, then a leaf strategy makes the final pick:

| Strategy | Type | Use |
|---|---|---|
| `ExplicitRoutingStrategy` | filter | caller forced a platform via `extra("platform", ...)` or a `openai:gpt-4o-mini` model prefix |
| `CapabilityRoutingStrategy` | filter | keep only candidates declaring the needed capability (e.g. `EMBED`) |
| `RoundRobinStrategy` | leaf | rotate across candidates (thread-safe `AtomicInteger`) |
| `WeightedRoutingStrategy` | leaf | pick by registration weight |
| `LowestLatencyStrategy` | leaf | lowest average latency in a sliding window |
| `LowestCostStrategy` | leaf | lowest input price (via `PriceCatalog`) |

The default chain is `new ExplicitRoutingStrategy(new CapabilityRoutingStrategy(new RoundRobinStrategy()))`.

## Failover

```java
FailoverConfig failover = FailoverConfig.builder().maxAttempts(3).build();
GatewayClient gateway = new GatewayClient(registry, strategy, failover);
```

- **Failover**: timeout, HTTP ≥ 500, and connection-level errors move to another healthy candidate.
- **No failover**: 4xx (401/403/429) — the same error usually reproduces on another vendor, so it throws immediately. Add `.retryable(AiRateLimitException.class)` if you want 429 to also move.

## Key pool rotation

When one platform has several keys to spread rate limits:

```java
ApiKeyProvider provider = new RoundRobinApiKeyProvider(
        List.of("sk-key-1", "sk-key-2", "sk-key-3"));

KeyRotatingClientDecorator rotating = new KeyRotatingClientDecorator(provider,
        apiKey -> OpenAiClient.builder().apiKey(apiKey).build());

registry.register("openai", rotating);   // transparent to callers
```

On 401/403 or 429 the decorator switches key and retries, up to one attempt per key.

## Tenant quota & budget

For multi-tenant setups, callers hold a virtual key; the gateway resolves the tenant
and enforces cost / token / QPS limits:

```java
TenantManager tenantManager = new TenantManager();
tenantManager.registerVirtualKey("vk-tenant-alice", "alice");
tenantManager.configureTenant("alice", TenantConfig.builder()
        .maxCostPerPeriod(10.0)          // $10 per period
        .maxTokensPerPeriod(1_000_000)
        .rateLimitQps(5)
        .budgetPeriod(Duration.ofDays(1))
        .build());

TenantAwareGatewayClient tenantClient =
        new TenantAwareGatewayClient(gateway, new BudgetEnforcer(tenantManager, aggregator),
                new CostCalculator(PriceCatalog.defaults()));

ChatResponse resp = tenantClient.chat(ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("Hello")))
        .extra("tenantId", "alice")
        .build());   // over-limit throws AiBudgetExceededException
```

## Next steps

- [Platforms](./platforms.md) · [Quick Start](./quickstart.md) · [CLI](./cli.md)
