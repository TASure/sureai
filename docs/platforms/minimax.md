# MiniMax（稀宇科技）

## 简介

MiniMax（稀宇科技）开放平台，OpenAI 兼容协议。`MiniMax-M3` 为最新旗舰（1M 上下文，支持 Agent 推理 / 工具调用 / 多模态输入），另有 M2.x 系列。

## 鉴权方式

`Authorization: Bearer <apiKey>`，无额外必需请求头。设置环境变量：

```bash
export SURE_AI_MINIMAX_API_KEY="..."
```

可选设置 `SURE_AI_MINIMAX_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.minimax.cn/v1
```

兼容引擎拼接 `/chat/completions`，即实际请求 `https://api.minimax.cn/v1/chat/completions`。多区域端点（通过 `AiConfig.Builder#baseUrl(String)` 覆盖）：

- 国内站（默认）：`https://api.minimax.cn/v1`
- 国际站：`https://api.minimax.io/v1`
- 历史域名 `https://api.minimaxi.com/v1`、`https://api.minimax.chat/v1` 仍按 OpenAI 兼容路径服务

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `MiniMaxModels.MINIMAX_M3` | `MiniMax-M3` | 最新旗舰（1M 上下文，Agent 推理 / 工具调用 / 多模态输入） |
| `MiniMaxModels.MINIMAX_M2_7` | `MiniMax-M2.7` | M2.x 系列 |
| `MiniMaxModels.MINIMAX_M2_5` | `MiniMax-M2.5` | M2.x 系列 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-minimax</artifactId>
    <version>1.4.0</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = MiniMaxUtil.chat(MiniMaxModels.MINIMAX_M3, "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("...").build();
MiniMaxClient client = new MiniMaxClient(config);
ChatResponse resp = client.chat(MiniMaxModels.MINIMAX_M3, "你好！");
client.close();
```

## 流式调用

```java
MiniMaxUtil.chatStream(
    ChatRequest.builder()
        .model(MiniMaxModels.MINIMAX_M3)
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

- 复用 `OpenAiCompatClient`，仅声明 `CHAT` / `CHAT_STREAM` 能力。
- **不支持 Embedding**：MiniMax 的 OpenAI 兼容文档仅提供 `/v1/chat/completions`，未提供 `/v1/embeddings`；调用 `embed` 时由基类 `guard(Capability.EMBED)` 在发请求前快速失败，抛出 `AiException`（消息 `"minimax does not support EMBED capability"`）。
- MiniMax-M3 的 `thinking.type=adaptive/disabled`、`reasoning_split` 等特有参数可通过 `ChatRequest.Builder#extra(String, Object)` 透传。

## 官方文档

https://platform.minimaxi.com/docs/api-reference/text-openai-api
