# 响应缓存（Response Cache）

sureai core 内置的对话响应缓存，用于把「非流式 chat」的成功结果在本地（或外部存储）
复用，避免对语义等价的请求重复打模型接口。特性**默认关闭、向后兼容**：不配置
`CacheStore` 时，客户端行为与之前完全一致，仅有一次 `null` 判断的开销。

- 包：`com.sure.ai.client.cache`
- 运行期依赖：仅 `sure-core 0.2.0`，**不引入任何第三方缓存库**（不依赖 Caffeine / Redis 客户端）。

## 架构

```
ChatRequest
    │
    ▼
ChatCacheKey.of(request)        ← 请求归一化 + SHA-256 hex（64 字符）
    │
    ▼
CacheStore.get(key)             ← SPI：内置 LruCacheStore，可换 Redis
    │ 命中
    ├─► 直接返回 ChatResponse（不发网络、不触发指标/重试）
    │ 未命中
    ▼
doPostRaw(...) → 2xx → parse → CacheStore.put(key, resp, ttl)
```

### 归一化策略（ChatCacheKey）

缓存键由 `ChatCacheKey.normalize(request)` 拼接归一化字符串后做 SHA-256 得到。

**参与 hash 的字段：**

| 字段 | 说明 |
| --- | --- |
| `model` | 模型名 |
| `messages` | 按请求顺序，逐条序列化 `role + content/parts + name + toolCallId + toolCalls`；`content` 为 null 时退化为 `parts` 序列化，保证「等价请求」hash 一致 |
| `temperature` / `topP` | 采样参数，影响输出分布 |
| `tools` | **先按 `function.name` 升序排序**，再序列化 name/description/parameters，保证工具列表顺序无关 |
| `responseFormat` | JSON Schema / 字符串，以 canonical JSON 序列化 |
| `reasoningEffort` | 推理强度 |
| `thinkingConfig` | 思考模式配置（影响输出，故参与） |
| `grounding` | 联网搜索配置 |

**不参与 hash 的字段：**

| 字段 | 原因 |
| --- | --- |
| `maxTokens` | 仅限制输出长度，不影响内容语义 |
| `n` | 候选数，缓存只存第一条 |
| `stream` | 流式响应不缓存 |
| `extraHeaders` | 传输层请求头，与响应内容无关 |

> 顺序无关性：`tools` 列表会先按工具名排序再序列化，因此两个请求仅工具声明顺序不同
> 会命中同一条缓存。`messages` 顺序敏感（对话上下文顺序有语义）。

### CacheStore SPI

```java
public interface CacheStore {
    ChatResponse get(String key);          // null = 未命中或已过期
    void put(String key, ChatResponse response, long ttlMillis); // ttlMillis<=0 用 store 默认
    void remove(String key);
    void clear();
}
```

`get` 返回 `null` 表示未命中或已过期。实现需自行保证线程安全。

### 内置 LruCacheStore

纯 JDK `LinkedHashMap`（`accessOrder=true`）+ `synchronized`：

- 容量满时淘汰最久未访问（LRU）条目；
- `get` 时惰性检查 TTL，过期立即删除并视为未命中；
- 构造器：
  - `LruCacheStore()` —— 容量 1024，TTL 5 分钟
  - `LruCacheStore(int capacity)` —— TTL 5 分钟
  - `LruCacheStore(int capacity, long defaultTtlMillis)`

## 快速上手

```java
AiConfig config = AiConfig.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .baseUrl("https://api.openai.com/v1")
    // 开启缓存：容量 1024 条，默认 TTL 5 分钟
    .cacheStore(new LruCacheStore(1024, Duration.ofMinutes(5).toMillis()))
    // 可选：覆盖 TTL（不设置则用 store 默认）
    .cacheTtl(Duration.ofMinutes(10))
    .build();

OpenAiCompatClient client = new OpenAiCompatClient(config);

// 第一次：发请求
ChatResponse r1 = client.chat(ChatRequest.builder().model("gpt-4o")
    .messages(ChatMessage.user("你好")).build());
// 第二次等价请求：命中缓存，不再发网络
ChatResponse r2 = client.chat(ChatRequest.builder().model("gpt-4o")
    .messages(ChatMessage.user("你好")).build());
```

## 缓存命中行为（重要设计取舍）

- 命中时**不发 HTTP**，直接返回缓存的 `ChatResponse`；
- 因此**不触发** `MetricsCollector.onRequestStart/Success`、不计数重试、不触发
  `RetryListener`——缓存命中不属于一次真实的模型调用，这是刻意的设计选择；
- 缓存写入只发生在**成功响应（2xx）**之后；错误（4xx/5xx 重试后仍失败）在
  `doPostRaw` 阶段即抛出，**不会写入缓存**；
- **流式（`stream=true`）不查缓存也不写缓存**，每次都走网络。

## TTL 与容量

- TTL 优先级：`AiConfig.cacheTtl()`（若设置）> `CacheStore` 构造时的默认 TTL；
- 容量仅对 `LruCacheStore` 有意义；外部存储（Redis）由服务端策略决定淘汰；
- 过期条目采用**惰性删除**：仅在 `get` 访问到时检查并清理，不开启后台线程，零额外开销。

## Redis 外部缓存（RedisCacheStore）

sureai 在 `sure-ai-rag` 模块内置了 `RedisCacheStore`（包 `com.sure.ai.rag.store.cache`），
实现 `CacheStore` SPI，**不引入 Lettuce/Jedis 等第三方 Redis 客户端**——基于 JDK `Socket`
复用 v1.8.0 抽取的共享 RESP2 编解码（`com.sure.ai.rag.store.resp.RespCodec`）。

```java
import com.sure.ai.rag.store.cache.RedisCacheStore;

AiConfig config = AiConfig.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .cacheStore(RedisCacheStore.builder()
        .host("127.0.0.1").port(6379)          // 默认 localhost:6379
        .password(null)                         // 可选，设置后首连接先发 AUTH
        .keyPrefix("sureai:cache:")             // 默认前缀
        .defaultTtlMillis(Duration.ofMinutes(5).toMillis())  // 默认 5 分钟，与 LRU 对齐
        .build())
    .build();
```

语义与内置 `LruCacheStore` 对齐：

- **命令**：`SET key value PX ttl`（写）、`GET key`（读）、`DEL key`（删）；
  清空用 `SCAN` 按 key 前缀迭代收集后批量 `DEL`（**不使用阻塞式 `KEYS`**）。
- **TTL**：`put` 的 `ttlMillis<=0` 时回退到 `defaultTtlMillis`（默认 5 分钟），
  以 Redis `PX` 毫秒落地；键过期由 Redis 服务端回收，`GET` 返回 `$-1` 即视为未命中。
- **序列化**：值直接存 `ChatResponse.rawJson()`（OpenAI 兼容原始响应体），
  取回时按响应侧字段（id/model/choices/message.content/reasoning_content/tool_calls/
  usage/annotations）还原为 `ChatResponse`，与首次解析路径同构、无损。
- **连接**：复用单条 Socket（`synchronized` 串行化），传输层失败（IO 异常或对端断连）
  自动重连重试一次；服务端 `−ERR` 应用错误不重试、直接抛 `AiException`（fail-fast），
  由上层决定是否降级为「不走缓存、直连模型」。
- **线程安全**：实现自带锁，可在多客户端间共享同一实例；用完调用 `close()` 释放连接。

> 注：`RedisCacheStore` 位于 `sure-ai-rag` 模块（与 `RedisVectorStore` 同模块、共享 RESP 编解码）。
> 若工程未依赖 `sure-ai-rag`，仍可自行实现 `CacheStore` SPI（例如引入 Lettuce），SPI 本身在 `sure-ai-core`。

## 注意事项

- **流式不缓存**：`chatStream` 与 `stream=true` 的 `chat` 每次都请求实时结果；
- **错误不缓存**：5xx / 4xx 响应不写入，下次等价请求会重试真实调用；
- **maxTokens 不参与 hash**：它只截断输出长度。若你希望「不同 maxTokens 视为不同缓存项」，
  需自定义 `CacheStore` 并在 key 生成时额外拼入该字段；
- **多模态内容**：`parts` 中的文本 / 图片 URL / 文件 ID 均参与归一化；
- **默认关闭**：不调用 `.cacheStore(...)` 时零开销，老代码无需任何改动。
