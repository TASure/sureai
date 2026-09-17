# 常见问题

## 为什么不直接用 langchain4j / Spring AI？

sureai 的定位是**接入层**而非应用框架：
- **零第三方依赖**：运行期仅 sure-core，不传递 Jackson / OkHttp / Reactor 等重量级依赖，适合对依赖体积敏感的项目。
- **静态工具类**：`OpenAiUtil.chat("gpt-4o", "你好")` 三行上手，不需要 Spring 容器或复杂配置。
- **国产平台全覆盖**：DeepSeek / Qwen / Zhipu / Moonshot / Doubao / Baidu 开箱即用，langchain4j 对国产平台支持滞后。
- **模块级隔离**：只引 OpenAI 不会拉入 Anthropic 的依赖，反之亦然。

如果你需要 RAG 编排、Agent 循环、记忆管理等高级能力，langchain4j / Spring AI 更合适；sureai 可以作为它们的底层接入替代。

## 为什么 JSON 要自研，不用 Jackson / Gson？

为了保持"运行期仅 sure-core"的零依赖承诺。Jackson 本身约 1.5MB，Gson 约 300KB，sureai 的自研 JSON 约 15KB 源码，覆盖大模型 API 所需的全部 JSON 能力（对象/数组/字符串/数字/布尔/null）。

## 测试为什么不访问真实 API？

- 可重复性：CI 环境无 API Key，且真实 API 有速率限制和费用。
- 确定性：本地 HttpServer mock 可以精确控制响应状态码、延迟、SSE 序列。
- 所有平台模块的测试均使用 `com.sun.net.httpserver.HttpServer` 在随机端口启动 mock 服务。

## 如何添加新平台？

1. 在父 POM `<modules>` 中注册新模块。
2. 创建 `sure-ai-<platform>/pom.xml`，仅依赖 `sure-ai-core`。
3. 实现 `<Pla>Client extends AbstractAiClient`（或继承 `OpenAiCompatClient`）。
4. 实现 `<Pla>Util` 静态入口、`<Pla>Models` 常量、`package-info.java`。
5. 编写基于本地 HttpServer 的测试。
6. 在 `sure-ai-bom` / `sure-ai-all` / README / docs 中登记。

## 流式响应如何使用？

```java
OpenAiUtil.chatStream(
    ChatRequest.builder().model("gpt-4o").messages(List.of(ChatMessage.user("你好"))).build(),
    chunk -> System.out.print(chunk.getDeltaText())
);
```

每个 `ChatStreamChunk` 包含增量文本 `deltaText`，最后一个 chunk 的 `finishReason` 非空表示结束。

## Function Calling 如何使用？

```java
ToolSpec tool = ToolSpec.of(ToolFunction.of("getWeather", "获取天气",
    "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"));
ChatResponse resp = OpenAiUtil.chat(ChatRequest.builder()
    .model("gpt-4o")
    .messages(List.of(ChatMessage.user("北京天气怎么样")))
    .tools(List.of(tool))
    .build());
// resp.getChoices().get(0).getMessage().getToolCalls() 获取模型返回的工具调用
```

## 支持哪些 Java 版本？

sureai 要求 **JDK 21+**，使用 `maven.compiler.release=21` 编译。CI 在 JDK 21 和 25 上验证。

## 为什么 Ollama 用 ndjson 而不是 SSE？

Ollama 原生 `/api/chat` 接口返回的是 **NDJSON**（每行一个 JSON 对象），而非标准 SSE。sureai 在 `OllamaClient` 中按行解析并映射为通用 `ChatStreamChunk`。
