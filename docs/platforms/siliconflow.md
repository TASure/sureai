# 硅基流动 SiliconFlow

## 简介

硅基流动（SiliconFlow / SiliconCloud）是开源模型聚合托管平台，OpenAI 兼容协议。`model` 形如 `组织/模型名`（如 `deepseek-ai/DeepSeek-V3`），同时提供对话与向量嵌入。

## 鉴权方式

`Authorization: Bearer <apiKey>`，无额外必需请求头。设置环境变量：

```bash
export SURE_AI_SILICONFLOW_API_KEY="..."
```

可选设置 `SURE_AI_SILICONFLOW_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.siliconflow.cn/v1
```

兼容引擎同时拼接 `/chat/completions` 与 `/embeddings`。多区域端点（通过 `AiConfig.Builder#baseUrl(String)` 覆盖）：国内站（默认）`https://api.siliconflow.cn/v1`；国际站 `https://api.siliconflow.com/v1`。

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `SiliconFlowModels.DEEPSEEK_V3` | `deepseek-ai/DeepSeek-V3` | 对话 |
| `SiliconFlowModels.QWEN25_72B_INSTRUCT` | `Qwen/Qwen2.5-72B-Instruct` | 对话 |
| `SiliconFlowModels.QWEN3_32B` | `Qwen/Qwen3-32B` | 对话 |
| `SiliconFlowModels.BGE_M3` | `BAAI/bge-m3` | 向量嵌入 |

> 平台为开源模型聚合托管，最新可用列表以官方模型库为准；推理模型的 `reasoning_content` 等特有字段由响应透传，无需客户端特殊处理。

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-siliconflow</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = SiliconFlowUtil.chat(SiliconFlowModels.DEEPSEEK_V3, "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("...").build();
SiliconFlowClient client = new SiliconFlowClient(config);
ChatResponse resp = client.chat(SiliconFlowModels.DEEPSEEK_V3, "你好！");
client.close();
```

## 流式调用

```java
SiliconFlowUtil.chatStream(
    ChatRequest.builder()
        .model(SiliconFlowModels.DEEPSEEK_V3)
        .messages(List.of(ChatMessage.user("讲个笑话")))
        .build(),
    chunk -> {
        if (chunk.deltaText() != null) {
            System.out.print(chunk.deltaText());
        }
    }
);
```

## 向量嵌入

```java
EmbeddingResponse resp = SiliconFlowUtil.embed(SiliconFlowModels.BGE_M3,
        List.of("hello", "你好"));
float[] vector = resp.embeddings().get(0);
System.out.println("维度: " + vector.length);
```

## 注意事项

- 复用 `OpenAiCompatClient`，声明 `CHAT` / `CHAT_STREAM` / `EMBED` 能力。
- 未提供 OpenAI 兼容的图像 / 视频 / TTS / STT / 内容审核 / 微调端点；调用这些方法时由基类 `guard(Capability)` 在发请求前快速失败。

## 官方文档

- 语言模型（Chat Completions）：https://docs.siliconflow.cn/cn/userguide/capabilities/text-generation
- 创建嵌入请求（Embeddings）：https://docs.siliconflow.cn/docs/api/embeddings-post
