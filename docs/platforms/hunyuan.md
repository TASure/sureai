# 腾讯混元 Hunyuan

## 简介

腾讯混元开放平台，OpenAI 兼容协议。`hunyuan-turbos-latest` 为当前主力对话模型，`hunyuan-t1-latest` 为推理模型，向量模型固定为 `hunyuan-embedding`（当前维度 1024）。

## 鉴权方式

`Authorization: Bearer <apiKey>`，无额外必需请求头。设置环境变量：

```bash
export SURE_AI_HUNYUAN_API_KEY="..."
```

可选设置 `SURE_AI_HUNYUAN_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.hunyuan.cloud.tencent.com/v1
```

兼容引擎同时拼接 `/chat/completions` 与 `/embeddings`。

> **迁移说明**：腾讯混元大模型相关功能正逐步迁移至 **TokenHub**；新模型服务可改用 TokenHub 的 OpenAI 兼容端点 `https://tokenhub.tencentmaas.com/v1`（通过 `AiConfig.Builder#baseUrl(String)` 覆盖，对应 `HunyuanClient.TOKENHUB_BASE_URL`）。

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `HunyuanModels.HUNYUAN_TURBOS_LATEST` | `hunyuan-turbos-latest` | 当前主力对话模型 |
| `HunyuanModels.HUNYUAN_T1_LATEST` | `hunyuan-t1-latest` | 推理模型 |
| `HunyuanModels.HUNYUAN_EMBEDDING` | `hunyuan-embedding` | 向量嵌入（固定 1024 维） |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-hunyuan</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = HunyuanUtil.chat(HunyuanModels.HUNYUAN_TURBOS_LATEST, "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("...").build();
HunyuanClient client = new HunyuanClient(config);
ChatResponse resp = client.chat(HunyuanModels.HUNYUAN_TURBOS_LATEST, "你好！");
client.close();
```

## 流式调用

```java
HunyuanUtil.chatStream(
    ChatRequest.builder()
        .model(HunyuanModels.HUNYUAN_TURBOS_LATEST)
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
// 向量模型固定为 HUNYUAN_EMBEDDING
EmbeddingResponse resp = HunyuanUtil.embed(List.of("hello", "你好"));
float[] vector = resp.embeddings().get(0);
System.out.println("维度: " + vector.length);
```

## 注意事项

- 复用 `OpenAiCompatClient`，声明 `CHAT` / `CHAT_STREAM` / `EMBED` 能力。
- 未提供 OpenAI 兼容的图像 / 视频 / TTS / STT / 内容审核 / 微调端点；调用这些方法时由基类 `guard(Capability)` 在发请求前快速失败。
- 混元特有参数（如 `enable_enhancement`）可通过 `ChatRequest.Builder#extra(String, Object)` 透传。

## 官方文档

https://cloud.tencent.com/document/product/1729/111007
