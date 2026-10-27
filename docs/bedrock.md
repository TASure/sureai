# sure-ai-bedrock（AWS Bedrock）

AWS Bedrock 接入模块，统一使用 **Converse API**，运行期零第三方依赖：HTTP 基于 JDK
`HttpClient`，鉴权为纯 JDK 实现的 AWS Signature V4（`HmacSHA256` / `SHA-256`），JSON 与 SSE
复用 sure-ai-core 自研实现。

官方文档：<https://docs.aws.amazon.com/bedrock/>

## 凭证配置

客户端可显式传入凭证，或由 `BedrockUtil` 从环境变量读取。环境变量优先级：
`SURE_AI_BEDROCK_*` 覆盖 AWS 标准变量。

| 用途 | Bedrock 专属（优先） | AWS 标准（兜底） | 必填 |
| --- | --- | --- | --- |
| Access Key ID | `SURE_AI_BEDROCK_ACCESS_KEY` | `AWS_ACCESS_KEY_ID` | 是 |
| Secret Access Key | `SURE_AI_BEDROCK_SECRET_KEY` | `AWS_SECRET_ACCESS_KEY` | 是 |
| 临时会话令牌 | `SURE_AI_BEDROCK_SESSION_TOKEN` | `AWS_SESSION_TOKEN` | 否 |
| 区域 | `SURE_AI_BEDROCK_REGION` | `AWS_REGION`，再兜底 `AWS_DEFAULT_REGION` | 是 |
| 默认模型 | `SURE_AI_BEDROCK_MODEL` | — | 否 |

缺少 Access Key / Secret Key / Region 时抛出带明确提示的 `AiException`。

## SigV4 签名流程

`AwsSigV4Signer` 按 AWS 官方三步流程签名：

1. **规范请求**：`POST` + 规范 URI（`/model/{modelId}/converse`）+ 空查询串 + CanonicalHeaders
   （`host`、`x-amz-date`、`x-amz-content-sha256`，有临时令牌时追加 `x-amz-security-token`）
   + SignedHeaders + `Hex(SHA-256(payload))`。
2. **待签字符串**：`AWS4-HMAC-SHA256` + `x-amz-date(UTC)` +
   `credentialScope = date/region/bedrock/aws4_request` + `Hex(SHA-256(规范请求))`。
3. **签名密钥链式派生**：`("AWS4"+secretKey) → date → region → bedrock → aws4_request`，
   逐级 `HmacSHA256`，最终对待签字符串计算 `HmacSHA256` 取十六进制。

`Authorization` 头形如：

```
AWS4-HMAC-SHA256 Credential=AKID/20260925/us-east-1/bedrock/aws4_request,
  SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=<hex>
```

运行时端点为 `https://bedrock-runtime.{region}.amazonaws.com`。

## Converse API 与模型兼容性

本模块统一走 Converse API（非各模型原生 `InvokeModel`）：

- 非流式：`POST /model/{modelId}/converse`
- 流式：`POST /model/{modelId}/converse-stream`（SSE）

| 模型 ID | 提供方 | 流式支持 |
| --- | --- | --- |
| `anthropic.claude-sonnet-4-0:1` | Anthropic | 是 |
| `anthropic.claude-3-5-sonnet-20240620-v1:0` | Anthropic | 是 |
| `anthropic.claude-3-5-haiku-20241022-v1:0` | Anthropic | 是 |
| `anthropic.claude-3-opus-20240229-v1:0` | Anthropic | 是 |
| `meta.llama3-70b-instruct-v1:0` | Meta | 是 |
| `meta.llama3-8b-instruct-v1:0` | Meta | 是 |
| `amazon.titan-text-express-v1` | Amazon | 是 |
| `amazon.nova-pro-v1:0` | Amazon | 是 |
| `amazon.nova-lite-v1:0` | Amazon | 是 |

> 模型可用性随区域与账户开通情况变化，最新清单以官方
> [Supported models](https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html) 为准。

### 与原生 InvokeModel 的差异

- Converse API 是 Bedrock 的统一抽象，**请求/响应格式与模型无关**：`messages[].role/content[]`、
  `system[]`、`inferenceConfig` 统一映射，不必为每个厂商写原生格式。
- 原生 `InvokeModel` 需要针对 Anthropic / Llama / Titan 各自的请求体 schema 与响应 schema
  （如 Claude 的 `anthropic_version`、Llama 的 `prompt`），本模块不直接使用。
- 流式 SSE 事件统一为 `messageStart / contentBlockStart / contentBlockDelta / contentBlockStop /
  messageStop / metadata`，与厂商无关。

## 请求 / 响应映射

请求体：

```json
{
  "system": [{ "text": "你是助手" }],
  "messages": [
    { "role": "user", "content": [{ "text": "hi" }] }
  ],
  "inferenceConfig": { "maxTokens": 1024, "temperature": 0.7, "topP": 0.9 }
}
```

映射到 core 通用模型：

- `system[]` ← `ChatMessage.system(...)`
- `messages[]` ← 其余 `ChatMessage`（`role` + `content[].text`）
- `inferenceConfig.{maxTokens,temperature,topP}` ← `ChatRequest.Builder` 对应字段
- 响应 `output.message.content[].text` → `ChatResponse.firstText()`
- 响应 `stopReason` → `Choice.finishReason`
- 响应 `usage.inputTokens/outputTokens/totalTokens` → `TokenUsage`
- 流式 `contentBlockDelta.delta.text` → `ChatStreamChunk.deltaText`
- 流式 `messageStop.stopReason` → `ChatStreamChunk.finishReason`

## 示例

```java
BedrockClient client = BedrockUtil.create(); // 读环境变量
ChatResponse resp = client.chat(ChatRequest.builder()
    .model(BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET)
    .messages(List.of(ChatMessage.user("用一句话介绍你自己")))
    .build());
System.out.println(resp.firstText());
client.close();
```

流式：

```java
client.chatStream(ChatRequest.builder()
        .model(BedrockModels.META_LLAMA3_70B)
        .messages(List.of(ChatMessage.user("讲个冷笑话")))
        .build(),
    chunk -> {
        if (chunk.deltaText() != null) {
            System.out.print(chunk.deltaText());
        }
    });
```

## 限制

- Embeddings（Amazon Titan Text Embeddings）暂未接入，`embedNotSupported()` 抛 `AiException`，
  后续将通过 `InvokeModel` 实现。
- 工具调用（tool use）仅透传基础文本；复杂结构化工具调用的端到端映射后续完善。
