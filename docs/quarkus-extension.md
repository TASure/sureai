# sure-ai-quarkus-extension

让 Quarkus 工程**零样板代码**接入 sureai：在 `application.properties`（或 `application.yml`）里配上平台的 `sure.ai.<platform>.api-key`，启动后即可按类型直接注入对应平台的 `XxxClient` Bean。

> 红线：本扩展是整个 sureai 工程中**唯一**引入 Quarkus 依赖的地方。`sure-ai-core` 与各平台模块运行期**零 Quarkus 依赖**，不感知 Quarkus，仍可在非 Quarkus 环境独立使用。

## 双模块结构

| 模块 | 作用 |
|------|------|
| `sure-ai-quarkus-extension` | runtime 侧：配置映射（`SureAiBuildConfig` / `PlatformConfig`）、纯逻辑工厂 `SureAiClientFactory`、`@Recorder SureAiRecorder` |
| `sure-ai-quarkus-extension-deployment` | 构建期：`SureAiProcessor`（`@BuildStep`）读取配置，为配了 `api-key` 的平台注册 Arc 合成 Bean |

`SureAiClientFactory` **不引入任何 Quarkus 类型**，可在不启动 Quarkus 容器的前提下做单元测试；deployment 模块的 Processor 与 runtime 侧的 Recorder 都只是它的薄封装。这与 Spring Boot Starter 中「读配置 → 构建 `AiConfig` → new 客户端」三步等价，不引入任何额外运行期行为，也不改变各平台客户端语义。

## 何时使用

- 你正在写一个 **Quarkus 3.x（JDK 21+）** 应用；
- 希望把连接配置交给 `application.properties`，少写 `new OpenAiClient(AiConfig...)` 样板。

非 Quarkus 工程请直接使用各平台模块的 `XxxUtil` 静态入口或手动 `new XxxClient(AiConfig)`，**不要**引入本扩展。

## 引入依赖

Maven：

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-quarkus-extension</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 配置示例（application.properties）

配置根前缀为 `sure.ai`（构建期 `@ConfigMapping`），各平台复用同一 `PlatformConfig` 组：

```properties
# OpenAI：缺省 baseUrl 即官方地址
sure.ai.openai.api-key=sk-xxxxxxxx
sure.ai.openai.model=gpt-4o-mini
# sure.ai.openai.base-url=                 # 可空，空则用官方默认
# sure.ai.openai.timeout=30s
# sure.ai.openai.max-retries=3
# sure.ai.openai.connect-timeout=10s
# sure.ai.openai.proxy=127.0.0.1:7890
# sure.ai.openai.organization=org-xxxxx
# sure.ai.openai.rate-limit-qps=5.0
# sure.ai.openai.cache-ttl=5m

# 同时接入多个平台，互不影响
sure.ai.qwen.api-key=sk-qwen-xxxxxxxx
sure.ai.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1

# 百度千帆：双密钥（api-key + secret-key）
# 扩展会自动把 secret-key 塞进 extraHeaders("secretKey", ...)，
# 与 BaiduClient#SECRET_KEY_HEADER 对齐。
sure.ai.baidu.api-key=your-baidu-api-key
sure.ai.baidu.secret-key=your-baidu-secret-key
```

### 配置键一览（`PlatformConfig`）

| 配置键 | 说明 |
| --- | --- |
| `api-key` | 平台凭证。**未配置时该平台不注册 Client Bean**（条件装配）。 |
| `secret-key` | 第二凭证，仅百度等双密钥平台；百度自动映射到 `extraHeaders("secretKey", ...)`。 |
| `base-url` | 可空，空则用平台客户端内置默认地址。 |
| `model` | 建议默认模型，仅供应用读取；模型是请求级参数，不进入 `AiConfig`。 |
| `timeout` | `Duration`，读超时。 |
| `connect-timeout` | `Duration`，连接超时。 |
| `proxy` | 代理 `host:port`，可空。 |
| `organization` | 组织 ID，可空。 |
| `max-retries` | 最大重试次数。 |
| `rate-limit-qps` | `double`，客户端令牌桶限流；`>0` 启用，`0`/空=关闭。 |
| `cache-ttl` | `Duration`，缓存 TTL。 |
| `extra-headers` | `Map<String,String>`，透传到 `AiConfig.extraHeaders`。 |

> **对象型扩展点不在配置中暴露**：`cacheStore` / `circuitBreaker` / `retryListeners` / `metricsCollector` 均为接口实现，扩展不代为注入——请在自己的 CDI 中声明对应 Bean 后通过 `AiConfig.Builder` 编程式挂载。

### 支持的平台前缀（共 23 个）

`openai` / `azure` / `anthropic` / `gemini` / `deepseek` / `qwen` / `zhipu` / `moonshot` / `doubao` / `baidu` / `ollama` / `grok` / `mistral` / `llamacpp` / `cohere` / `minimax` / `stepfun` / `baichuan` / `lingyi` / `siliconflow` / `hunyuan` / `spark` 走 `PlatformConfig`（条件装配前缀 `api-key`）；`bedrock` 走独立的 `BedrockGroup`（条件装配前缀 `access-key`，SigV4 四元组凭证）。

```properties
# AWS Bedrock：SigV4 鉴权，不复用 PlatformConfig
sure.ai.bedrock.access-key=AKIAXXXXXXXXXXXXXXXX
sure.ai.bedrock.secret-key=xxxxxxxxxxxxxxxxxxxxxxxx
sure.ai.bedrock.region=us-east-1
# sure.ai.bedrock.session-token=          # 可选，STS 临时令牌
# sure.ai.bedrock.model=anthropic.claude-3-5-sonnet-20240620-v1:0
```

## 注入使用

每个已配置 `api-key` 的平台，其 `XxxClient` 会被注册为 Arc 合成 Bean（`@Singleton` 作用域，**按客户端类型注入**）：

```java
@ApplicationScoped
public class ChatService {

    // 仅当 sure.ai.openai.api-key 配置后容器里才有这个 Bean
    @Inject
    OpenAiClient openAi;

    public String ask(String prompt) {
        return openAi.chat("gpt-4o-mini", prompt).content().text();
    }
}
```

多平台并存时按类型分别注入：`OpenAiClient`、`QwenClient`、`AnthropicClient`……它们都实现统一的 `AiClient` 接口。

## 条件装配行为

- **配了 `api-key`** → 构建期经 `SyntheticBeanBuildItem` 注册对应 `XxxClient` 为 `@Singleton` 合成 Bean；
- **没配 `api-key`** → 该平台不注册，启动不报错、不发网络请求；
- 实例化被 `@Record(ExecutionTime.STATIC_INIT)` 录制到运行期，经 `SureAiRecorder` 委托 `SureAiClientFactory` 完成「构建 `AiConfig` → 反射 new 客户端」；实例化本身不触发任何网络请求；
- Bedrock 条件为 `sure.ai.bedrock.access-key` 存在。

启动日志 `Installed features` 中可见扩展特性名 `sure-ai`。

## 与手动 new 的等价性

扩展做的事完全等价于：

```java
OpenAiClient client = new OpenAiClient(
    AiConfig.builder()
        .apiKey("sk-xxxxxxxx")
        .baseUrl("https://your-proxy/v1")
        .timeout(Duration.ofSeconds(30))
        .maxRetries(3)
        .build());
```

只是把「读配置 → 构建 `AiConfig` → new 客户端」自动化，**不改变任何平台客户端语义**。

## 设计红线

- `sure-ai-core` 与各平台模块**零 Quarkus 依赖**，可独立使用；
- Quarkus 相关注解与依赖**仅**存在于本扩展两个模块；
- runtime 侧 `SureAiClientFactory` 不引入 Quarkus 类型，便于纯单测。
