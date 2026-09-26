# OpenAI 兼容代理（sure-ai-proxy）

独立的 OpenAI 兼容 HTTP 代理服务（LiteLLM proxy 模式）。把 `GatewayClient` 的多供应商路由、故障转移能力以 OpenAI API 协议暴露——任何 OpenAI SDK 只需把 `baseUrl` 指向 `http://host:port/v1` 即可使用全部已注册平台能力。

- 模块坐标：`io.github.tasure:sure-ai-proxy`
- 包路径：`com.sure.ai.proxy`
- 基于 JDK 内置 `com.sun.net.httpserver.HttpServer`，零新依赖
- 平台隔离：代理模块只依赖 `sure-ai-core` + `sure-ai-gateway`，不 import 任何具体平台模块

## 快速上手

```java
import com.sure.ai.gateway.ClientRegistry;
import com.sure.ai.gateway.GatewayClient;
import com.sure.ai.proxy.ProxyConfig;
import com.sure.ai.proxy.SureAiProxy;

// 1. 注册平台 client 并创建网关
ClientRegistry registry = new ClientRegistry();
registry.register("openai", openAiClient);
GatewayClient gateway = new GatewayClient(registry);

// 2. 创建配置（端口 8080，虚拟密钥映射）
ProxyConfig config = ProxyConfig.builder()  // 见下方 ProxyConfig 章节
    // ...
    .build();

// 3. 启动代理
SureAiProxy proxy = new SureAiProxy(gateway, registry, config);
proxy.start();
System.out.println("代理已启动: http://localhost:" + proxy.boundPort() + "/v1");
```

启动后，用 curl 测试：

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-virtual-key" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"你好"}]}'
```

## 端点列表

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/chat/completions` | 对话补全（非流式 + SSE 流式 `stream:true`） |
| GET | `/v1/models` | 列出配置对外暴露的模型 |
| POST | `/v1/embeddings` | 向量嵌入（路由到声明 `EMBED` 能力的客户端；未注册返回 501） |

## OpenAI 兼容格式

### 请求体（/v1/chat/completions）

```json
{
  "model": "gpt-4o-mini",
  "messages": [{"role": "user", "content": "你好"}],
  "stream": false,
  "temperature": 0.7
}
```

### 非流式响应

标准 OpenAI chat completion JSON 格式（`id` / `object` / `choices` / `usage` 等字段）。

### SSE 流式格式

`stream:true` 时响应 `Content-Type: text/event-stream`，逐片推送：

```
data: {"id":"...","choices":[{"delta":{"content":"你"}}]}

data: {"id":"...","choices":[{"delta":{"content":"好"}}]}

data: [DONE]

```

每条 SSE 事件以 `data: ` 开头、`\n\n` 结尾，结束时发送 `data: [DONE]\n\n`。

### 错误响应

```json
{
  "error": {
    "message": "Invalid API key",
    "type": "invalid_request_error",
    "code": "invalid_api_key"
  }
}
```

## 虚拟密钥鉴权

每个请求校验 `Authorization: Bearer <virtualKey>`。虚拟密钥与真实平台 key 解耦——调用方持有代理签发的虚拟 key，代理再凭注册的下游 Client 访问真实平台。

```java
import com.sure.ai.proxy.VirtualKeyAuth;

VirtualKeyAuth auth = new VirtualKeyAuth(Map.of(
    "sk-tenant1-key", "tenant1",
    "sk-tenant2-key", "tenant2"
));

// 鉴权（在 ProxyConfig 中配置，代理自动处理）
String tenant = auth.authenticate("Bearer sk-tenant1-key");  // "tenant1"
String fail = auth.authenticate("Bearer wrong-key");           // null → 401
```

解析出的 tenantId 会写入 `ChatRequest.extra["tenantId"]`，供网关层做多租户配额。

### 401 错误

缺少 `Authorization` 头、缺少 `Bearer` 前缀或密钥未注册时，返回 401：

```json
{
  "error": {
    "message": "Invalid API key",
    "type": "invalid_request_error",
    "code": "invalid_api_key"
  }
}
```

## ProxyConfig

仅用 `java.util.Properties` 加载，不引入任何配置库。不可变。

### 配置项

| 配置键 | 说明 | 默认值 |
|--------|------|--------|
| `proxy.port` | 监听端口 | `8080` |
| `proxy.default.model` | 请求未携带 model 时的兜底模型 | `gpt-4o` |
| `proxy.models` | 对外暴露的模型列表（逗号分隔，`/v1/models` 返回） | 仅默认模型 |
| `proxy.key.<virtualKey>` | 虚拟密钥 → 租户 ID 映射 | 无 |

### 配置文件示例

`sure-ai-proxy.properties`：

```properties
proxy.port=8080
proxy.default.model=gpt-4o
proxy.models=gpt-4o,gpt-4o-mini,text-embedding-3-small
proxy.key.sk-tenant1-key=tenant1
proxy.key.sk-tenant2-key=tenant2
```

### 编程式加载

```java
import com.sure.ai.proxy.ProxyConfig;
import java.nio.file.Path;
import java.util.Properties;

// 默认配置
ProxyConfig defaults = ProxyConfig.defaults();

// 从 Properties 对象
Properties props = new Properties();
props.setProperty("proxy.port", "9090");
ProxyConfig config = ProxyConfig.fromProperties(props);

// 从文件路径
ProxyConfig fromFile = ProxyConfig.load(Path.of("sure-ai-proxy.properties"));
```

查询方法：`config.port()` / `config.defaultModel()` / `config.exposedModels()` / `config.keyToTenant()`。

## main 启动方式

```bash
# 使用默认配置文件名 sure-ai-proxy.properties
java -cp sure-ai-proxy.jar com.sure.ai.proxy.SureAiProxy

# 指定配置文件路径
java -cp sure-ai-proxy.jar com.sure.ai.proxy.SureAiProxy /path/to/config.properties
```

> `main` 入口仅注册空 `ClientRegistry`，不挂载任何平台 Client——真实使用请通过编程式构造器在注册完平台 Client 后再 `start()`。

## 错误码映射

| HTTP 状态码 | 触发条件 | type | code |
|-------------|---------|------|------|
| 400 | 请求体解析失败 / 字段缺失 | `invalid_request_error` | `bad_request` |
| 401 | 虚拟密钥鉴权失败 | `invalid_request_error` | `invalid_api_key` |
| 405 | 请求方法不对（如 GET /chat/completions） | `invalid_request_error` | `method_not_allowed` |
| 501 | `/v1/embeddings` 但未注册 EMBED 客户端 | `not_implemented` | `no_embedding_client` |
| 502 | 上游（网关）调用失败 | `upstream_error` | `gateway_error` |

## 完整生命周期

```java
SureAiProxy proxy = new SureAiProxy(gateway, registry, config);
proxy.start();                    // 绑定端口并异步监听
int port = proxy.boundPort();    // 获取实际端口（配置 0 时为随机端口）
// ... 服务运行 ...
proxy.stop();                     // 停止服务并释放线程池
```

线程池大小为 `max(2, availableProcessors * 2)`，服务停止时自动 shutdown。
