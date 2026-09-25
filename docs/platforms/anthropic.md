# Anthropic Claude

## 简介

Anthropic 公司的 Claude 系列模型，提供 opus/sonnet/haiku 三个档位。sureai 的 Anthropic 模块适配其 Messages API 协议。

## 鉴权方式

使用 `x-api-key` 请求头鉴权。在 [Anthropic Console](https://console.anthropic.com/) 创建 API Key。设置环境变量：

```bash
export SURE_AI_ANTHROPIC_API_KEY="sk-ant-..."
```

可选设置 `SURE_AI_ANTHROPIC_BASE_URL` 覆盖默认地址。

## 默认 Endpoint

```
https://api.anthropic.com/v1
```

## 模型 ID

| 常量 | 模型 ID | 说明 |
|------|---------|------|
| `AnthropicModels.CLAUDE_OPUS_4_7` | `claude-opus-4-7` | Opus 旗舰 |
| `AnthropicModels.CLAUDE_OPUS_4_6` | `claude-opus-4-6` | Opus 前代 |
| `AnthropicModels.CLAUDE_SONNET_4_6` | `claude-sonnet-4-6` | Sonnet 均衡 |
| `AnthropicModels.CLAUDE_SONNET_4_5` | `claude-sonnet-4-5` | Sonnet 前代 |
| `AnthropicModels.CLAUDE_HAIKU_4_5` | `claude-haiku-4-5` | Haiku 轻量 |

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-anthropic</artifactId>
    <version>1.2.1-SNAPSHOT</version>
</dependency>
```

## 基础用法

### 静态工具类

```java
ChatResponse resp = AnthropicUtil.chat("claude-haiku-4-5", "你好！");
System.out.println(resp.firstText());
```

### Builder + Client

```java
AiConfig config = AiConfig.builder().apiKey("sk-ant-...").build();
AnthropicClient client = new AnthropicClient(config);
ChatResponse resp = client.chat("claude-haiku-4-5", "你好！");
client.close();
```

## 流式调用

```java
AnthropicUtil.chatStream(
    ChatRequest.builder()
        .model("claude-haiku-4-5")
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

- 请求头自动携带 `anthropic-version: 2023-06-01`
- **暂不支持 Embedding**（Anthropic 目前未提供向量 API）
- 支持 Function Calling
- Anthropic 的 system prompt 通过 `ChatMessage.system()` 传入

## 官方文档

https://docs.anthropic.com/en/api/messages
