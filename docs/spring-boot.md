# sure-ai-spring-boot-starter

让 Spring Boot 工程**零样板代码**接入 sureai：在 `application.yml` 里配上平台的 `api-key`，启动后即可直接注入对应平台的 `XxxClient` Bean。

> 红线：本模块是整个 sureai 工程中**唯一**引入 Spring 依赖的模块。`sure-ai-core` 与各平台模块运行期**零第三方依赖**，不感知 Spring，仍可在非 Spring 环境独立使用。

## 何时使用

- 你正在写一个 **Spring Boot 3.3+（JDK 21+）** 应用；
- 希望少写 `new OpenAiClient(AiConfig...)` 这类样板，把连接配置交给 `application.yml`。

非 Spring 工程请直接使用各平台模块的 `XxxUtil` 静态入口或手动 `new XxxClient(AiConfig)`，**不要**引入本 starter。

## 引入依赖

本模块**不进 `sure-ai-all` 聚合**，也不在任何运行期依赖链上——只有显式引入它的 Spring Boot 工程才会触发自动装配。

Maven：

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-spring-boot-starter</artifactId>
    <version>1.4.0</version>
</dependency>
```

Gradle：

```groovy
implementation 'io.github.tasure:sure-ai-spring-boot-starter:1.4.0'
```

starter 内部通过 `spring-boot-dependencies` BOM（3.3.5）管理 Spring 版本，**不需要**父工程额外声明 Spring 版本。

## 配置示例（application.yml）

```yaml
sure:
  ai:
    # OpenAI：缺省 baseUrl 即官方地址
    openai:
      api-key: sk-xxxxxxxx
      model: gpt-4o-mini          # 建议默认模型（请求级参数，仅便于应用读取）
      # base-url: https://your-proxy/v1   # 可空，空则用官方默认
      timeout: 30s                 # 可空
      max-retries: 3               # 可空
      # —— v1.3.0 新增的通用连接参数（所有走 PlatformProperties 的平台都支持）——
      connect-timeout: 10s         # 连接超时，可空（AiConfig 默认 10s）
      proxy: 127.0.0.1:7890        # 代理 host:port，可空
      organization: org-xxxxx      # OpenAI Organization，可空
      rate-limit-qps: 5.0          # 客户端令牌桶限流，>0 启用，0/空=关闭
      cache-ttl: 5m                # 缓存 TTL，可空（空则用 CacheStore 默认）
      extra-headers:                # 透传到 AiConfig.extraHeaders，可空
        X-Custom: foo

    # 同时接入多个平台，互不影响
    qwen:
      api-key: sk-qwen-xxxxxxxx
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1

    anthropic:
      api-key: sk-ant-xxxxxxxx

    zhipu:
      api-key: your-zhipu-apikey

    doubao:
      api-key: your-ark-api-key

    # 百度千帆：双密钥（api-key + secret-key）。
    # starter 会自动把 secret-key 塞进 extraHeaders("secretKey", ...)，
    # 与 BaiduClient#SECRET_KEY_HEADER 对齐，应用无需再手动 extraHeader。
    baidu:
      api-key: your-baidu-api-key
      secret-key: your-baidu-secret-key

    # Ollama 本地服务无需真实凭证，按 OllamaUtil 惯例填占位 key 并指向实际地址
    ollama:
      api-key: ollama-local
      base-url: http://localhost:11434
```

### v1.3.0 新增平台

v1.3.0 在原有 11 个平台基础上新增 5 个平台（grok / mistral / llamacpp / cohere / bedrock），Spring Starter 现在共支持 **16 个平台**。其中前 4 个复用 `PlatformProperties`，仅需 `api-key` 即可触发装配；Bedrock 走 AWS SigV4 四元组凭证，使用独立的 `BedrockProperties`。

```yaml
sure:
  ai:
    # xAI Grok（OpenAI 兼容协议）
    grok:
      api-key: xai-xxxxxxxx
      base-url: https://api.x.ai/v1     # 可空，空则用 GrokClient 内置默认地址
      model: grok-2

    # Mistral AI
    mistral:
      api-key: mistral-xxxxxxxx
      base-url: https://api.mistral.ai/v1   # 可空
      model: mistral-small-latest

    # Llama.cpp 本地 OpenAI 兼容服务
    llamacpp:
      api-key: llamacpp-local           # 本地服务可填占位 key
      base-url: http://localhost:8080/v1
      model: your-local-model

    # Cohere
    cohere:
      api-key: cohere-xxxxxxxx
      base-url: https://api.cohere.ai/v1      # 可空
      model: command-r-plus

    # AWS Bedrock：SigV4 鉴权，不复用 PlatformProperties。
    # 装配条件：sure.ai.bedrock.access-key 存在即注册 BedrockClient；
    # region 必填，为空时 BedrockClient 构造器会直接抛错。
    # timeout / max-retries 当前仅作配置占位（BedrockClient 自建 JDK HttpClient，
    # 暂未透传），保留字段是为了与其它平台配置风格一致。
    bedrock:
      access-key: AKIAXXXXXXXXXXXXXXXX        # 必填，对应 AWS_ACCESS_KEY_ID
      secret-key: xxxxxxxxxxxxxxxxxxxxxxxx    # 必填，对应 AWS_SECRET_ACCESS_KEY
      session-token: xxxxxxxx                 # 可选，临时会话令牌（STS）
      region: us-east-1                       # 必填
      model: anthropic.claude-3-5-sonnet-20240620-v1:0   # 可选
      # timeout: 60s                          # 占位，当前未透传
      # max-retries: 2                        # 占位，当前未透传
```

每个可走 `PlatformProperties` 的平台可配置的字段一致：

| 字段 | 说明 |
| --- | --- |
| `api-key` | 平台凭证。**未配置时该平台不装配 Bean**（条件装配）。 |
| `secret-key` | 第二凭证，仅百度等双密钥平台使用；百度会被自动映射到 `extraHeaders("secretKey", ...)`（对齐 `BaiduClient#SECRET_KEY_HEADER`）。 |
| `base-url` | 可空，空则用平台客户端内置默认地址。 |
| `model` | 建议默认模型，仅供应用读取；模型是请求级参数，不进入 `AiConfig`。 |
| `timeout` | `Duration`，可空（缺省 60s）。 |
| `connect-timeout` | `Duration`，可空（缺省 10s）。 |
| `proxy` | 代理 `host:port`，可空。 |
| `organization` | 组织 ID（如 OpenAI Organization），可空。 |
| `max-retries` | 整数，可空（缺省 2）。 |
| `rate-limit-qps` | `double`，客户端令牌桶限流；`>0` 启用，`0`/空=关闭。 |
| `cache-ttl` | `Duration`，缓存 TTL，可空（空则用 `CacheStore` 自带默认 TTL）。 |
| `extra-headers` | `Map<String,String>`，额外请求头，透传到 `AiConfig.extraHeaders`。 |
| `enabled` | 默认 `true`。 |

> **对象型扩展点不在 yml 暴露**：`cacheStore` / `circuitBreaker` / `retryListeners` / `metricsCollector` 均为接口实现，Spring 无法按字符串实例化，因此 `PlatformProperties` 不暴露这四个字段。需要时请在自己的 `@Configuration` 中声明对应类型的 `@Bean`，再通过 `AiConfig.Builder` 编程式挂载（starter 不代为注入，避免隐式行为）。

支持的平台前缀（共 16 个）：`openai` / `azure` / `anthropic` / `gemini` / `deepseek` / `qwen` / `zhipu` / `moonshot` / `doubao` / `baidu` / `ollama` / `grok` / `mistral` / `llamacpp` / `cohere` / `bedrock`。其中前 15 个走 `PlatformProperties`（条件装配前缀 `api-key`），`bedrock` 走 `BedrockProperties`（条件装配前缀 `access-key`）。

## 注入使用

```java
@Service
public class ChatService {

    // 仅当 sure.ai.openai.api-key 配置后容器里才有这个 Bean
    private final OpenAiClient openAi;

    public ChatService(OpenAiClient openAi) {
        this.openAi = openAi;
    }

    public String ask(String prompt) {
        return openAi.chat("gpt-4o-mini", prompt).content().text();
    }
}
```

多平台并存时按需要分别注入：`OpenAiClient`、`QwenClient`、`AnthropicClient`……它们都实现统一的 `AiClient` 接口。

## 条件装配行为

- **配了 `api-key`** → 注册对应 `XxxClient` Bean；
- **没配 `api-key`** → 该平台不出现在容器中，启动不报错、不发网络请求；
- 你自己声明了同类型 Bean（如手动 `@Bean OpenAiClient`）→ starter 自动退让（`@ConditionalOnMissingBean`），以你的为准；
- 多个平台同时配置时各自独立装配，互不影响。

## 与手动 new 的等价性

starter 做的事完全等价于：

```java
OpenAiClient client = new OpenAiClient(
    AiConfig.builder()
        .apiKey("sk-xxxxxxxx")
        .baseUrl("https://your-proxy/v1")
        .timeout(Duration.ofSeconds(30))
        .maxRetries(3)
        .build());
```

只是把「读配置 → 构建 `AiConfig` → new 客户端」自动化，**不改变任何平台客户端语义**。特殊平台的进阶参数（Azure 的 `api-version`、百度的 access_token 换取、智谱的 JWT 等）仍走各自客户端约定，需要时可手动构造 Bean 覆盖。

## RAG / Agent

`sure.ai.rag.*`、`sure.ai.agent.*` 为预留配置骨架（`enabled`、`embedding-model`、`chunk-size` 等），当前版本仅声明开关，后续版本会在此基础上装配 `RagPipeline` / `ToolRegistry` Bean。现阶段如需 RAG/Agent 能力，仍请在应用中手动组装。

## 设计红线

- `sure-ai-core` 与各平台模块**零 Spring 依赖**，可独立使用；
- Spring 相关注解与依赖**仅**存在于本 starter 模块；
- 本模块**不进 `sure-ai-all`**，也不在任何模块的传递依赖链上；
- 运行期唯一第三方依赖仍是 `sure-core 0.2.0`（由 core 模块带来）。
