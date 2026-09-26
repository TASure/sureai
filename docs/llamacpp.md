# llama.cpp 平台

> 模块：`sure-ai-llamacpp` ｜ Base URL：`http://localhost:8080/v1` ｜ 协议：OpenAI 兼容（本地推理服务器）

## 快速开始

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-llamacpp</artifactId>
    <version>1.4.0</version>
</dependency>
```

启动 llama-server：

```bash
llama-server -m model.gguf -a my-model --port 8080
# 如需 Embeddings：加 --embedding
# 如需鉴权：加 --api-key "your-secret"
```

使用 SDK：

```java
LlamaCppClient client = new LlamaCppClient(
    AiConfig.builder()
        .baseUrl("http://localhost:8080/v1")
        .apiKey("dummy")  // 无鉴权时用任意非空值
        .build()
);
ChatResponse resp = client.chat(ChatRequest.builder()
    .model("my-model")  // 与启动 -a 指定的 alias 一致
    .messages(List.of(ChatMessage.user("你好")))
    .build());
```

便捷入口：

```java
ChatResponse resp = LlamaCppUtil.chat("my-model", "你好");
```

## 模型

模型 ID 由启动参数 `-a`（alias）决定，`LlamaCppModels` 提供常见别名常量参考：

| 常量 | 别名 |
|---|---|
| `LLAMA_3_1_8B` | `llama-3.1-8b` |
| `LLAMA_3_1_70B` | `llama-3.1-70b` |
| `QWEN2_5_7B` | `qwen2.5-7b` |
| `MISTRAL_7B` | `mistral-7b` |
| `PHI_4` | `phi-4` |
| `BGE_M3` | `bge-m3` |

实际 ID 以 `GET /v1/models` 返回为准。

## 能力矩阵

| 能力 | 支持 | 说明 |
|---|---|---|
| Chat | ✅ | `/v1/chat/completions`，OpenAI 兼容 |
| Stream | ✅ | SSE `chat.completion.chunk` |
| Embedding | ✅ | `/v1/embeddings`，**需启动加 `--embedding`** |
| Models | ✅ | `GET /v1/models`，返回已加载模型 alias |
| Function Calling | ✅ | 取决于模型支持 |
| 结构化输出 | ✅ | `response_format`（json_object / json_schema） |

## 鉴权

- 默认无鉴权（`apiKey` 传 `"dummy"` 即可，`applyAuth` 对 dummy key 不发 Bearer 头）
- 启动加 `--api-key "secret"` 后，配置真实 key 即发 `Authorization: Bearer secret`

## 特殊参数

llama.cpp 支持通过 `ChatRequest.extra()` 透传特有参数：

- `grammar`：GBNF 文法约束生成
- `response_format.json_schema`：JSON Schema 约束
- 原生 `/completion` 端点的 `n_predict` 等（OpenAI 兼容端点映射为 `max_tokens`）

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `SURE_AI_LLAMACPP_BASE_URL` | ❌ | 覆盖默认 `http://localhost:8080/v1` |
| `SURE_AI_LLAMACPP_API_KEY` | ❌ | 鉴权 key（默认 `dummy`） |

## 官方文档

https://github.com/ggml-org/llama.cpp/blob/master/examples/server/README.md
