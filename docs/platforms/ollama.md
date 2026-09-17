# Ollama

## 简介

Ollama 是本地大模型运行工具，支持在本地运行 Llama、Qwen、Gemma、Mistral 等开源模型。无需 API Key，完全本地部署。

## 鉴权方式

**无需 API Key**。Ollama 为本地服务，默认监听 `localhost:11434`。

可选设置 `SURE_AI_OLLAMA_BASE_URL` 覆盖默认地址（如远程 Ollama 实例）。

## 默认 Endpoint

```
http://localhost:11434
```

## 模型 ID

使用前需通过 `ollama pull <name>` 下载模型：

| 常量 | 模型名 | 说明 |
|------|--------|------|
| `OllamaModels.LLAMA3_2` | `llama3.2` | Meta Llama 3.2 |
| `OllamaModels.QWEN2_5` | `qwen2.5` | 通义千问开源版 |
| `OllamaModels.GEMMA2` | `gemma2` | Google Gemma 2 |
| `OllamaModels.MISTRAL` | `mistral` | Mistral |
| `OllamaModels.PHI3` | `phi3` | 微软 Phi-3 |

向量模型推荐 `nomic-embed-text`：

```bash
ollama pull nomic-embed-text
```

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-ollama</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
// 自动连接 localhost:11434
ChatResponse resp = OllamaUtil.chat("llama3.2", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder()
    .apiKey("ollama-local")
    .baseUrl("http://localhost:11434")
    .build();
OllamaClient client = new OllamaClient(config);
ChatResponse resp = client.chat("llama3.2", "你好！");
client.close();
```

## 流式调用

```java
OllamaUtil.chatStream(
    ChatRequest.builder()
        .model("llama3.2")
        .messages(List.of(ChatMessage.user("讲个笑话")))
        .build(),
    chunk -> {
        if (chunk.deltaText() != null) {
            System.out.print(chunk.deltaText());
        }
    }
);
```

## 注意事项

- **无需 API Key**，完全本地运行
- 流式响应使用 **ndjson** 格式（每行一个 JSON 对象），非 SSE
- 支持 Embedding（需 `ollama pull nomic-embed-text`）
- 使用前务必先 `ollama pull <model>` 下载模型
- 支持 Function Calling

## 官方文档

https://docs.ollama.com/api/chat
