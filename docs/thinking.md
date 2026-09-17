# 思考模式（Reasoning / Thinking）

sureai 对模型的「深度思考 / 思维链」能力做了统一抽象：请求侧用
`ChatRequest.builder().reasoningEffort(...)` 与 `thinkingConfig(...)` 两个字段承载各平台差异，
响应侧统一把模型的思维链解析到 `ChatMessage.reasoningContent()`，业务代码无需关心各平台字段名。

## 1. 设计概述

```
ChatRequest
  ├── reasoningEffort(String)   OpenAI reasoning_effort: minimal/low/medium/high
  └── thinkingConfig(Object)    平台差异透传（Gemini thinkingConfig / Claude budget_tokens / Qwen 预算）

ChatResponse.choices[0].message.reasoningContent()   ← 统一思维链出口
```

**两套入口的分工**

- `reasoningEffort(String)`：对齐 OpenAI 的枚举强度（`minimal`/`low`/`medium`/`high`），
  OpenAI / Azure 直接序列化为 `reasoning_effort`。
- `thinkingConfig(Object)`：承载平台私有配置（Gemini 的 `thinkingConfig`、Anthropic 的
  `thinking.budget_tokens`、Qwen 的 `thinking_budget`），类型可为 `String` / `Map` / JsonObject。

## 2. 快速上手

```java
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.openai.OpenAiUtil;

ChatRequest req = ChatRequest.builder()
    .model("o4-mini")
    .messages(List.of(ChatMessage.user("一个水池进水管5小时注满，出水管8小时放空，几小时注满？")))
    .reasoningEffort("high")          // OpenAI / Azure
    .build();

ChatResponse resp = OpenAiUtil.chat(req);
String reasoning = resp.choices().get(0).message().reasoningContent();  // 思维链
String answer    = resp.firstText();                                    // 正式回答
```

## 3. 各平台字段差异

| 平台 | 请求字段映射 | 响应思维链解析 |
|---|---|---|
| OpenAI / Azure | `reasoningEffort` → `reasoning_effort` | 消息 `reasoning_content` → `reasoningContent` |
| Gemini | `thinkingConfig` → `generationConfig.thinkingConfig` | `parts[].thought == true` 部分文本 → `reasoningContent` |
| Anthropic | `thinkingConfig` → `thinking` 字段（如 `{type:enabled, budget_tokens:N}`） | `content[]` 中 `type:"thinking"` block → `reasoningContent` |
| 通义千问 Qwen | `reasoningEffort`/`thinkingConfig` → `enable_thinking` + `thinking_budget`（**覆盖序列化，移除 `reasoning_effort`**） | 消息思维链字段 → `reasoningContent` |

> DeepSeek / Moonshot 等以 `reasoner` 模型自带思维链返回，无需显式开关；其余未适配平台
> 会忽略这两个字段，不影响普通对话。

## 4. 响应解析

`ChatMessage.reasoningContent()` 返回模型的中间推理过程，可能为 `null`（模型/账号未开启思考，
或非推理模型）。正式回答仍从 `ChatResponse.firstText()` 取：

| 出口 | 类型 | 说明 |
|---|---|---|
| `ChatMessage.reasoningContent()` | String | 思维链，可能为 `null` |
| `ChatResponse.firstText()` | String | 正式回答正文 |

## 5. 平台对比表

| 平台 | 思考模式 | 请求入口 | 思维链出口 |
|---|---|---|---|
| OpenAI | ✅ | `reasoningEffort` | `reasoning_content` |
| Azure OpenAI | ✅ | `reasoningEffort` | `reasoning_content` |
| Google Gemini | ✅ | `thinkingConfig` | `parts[].thought` |
| Anthropic | ✅ | `thinkingConfig` | `type:thinking` block |
| 通义千问 Qwen | ✅ | `enable_thinking` + `thinking_budget` | 消息思维链字段 |
| 智谱 / 豆包 / 百度 / DeepSeek / Moonshot / Ollama | ❌ | 暂未单独抽象（DeepSeek reasoner 模型原生自带） | — |

详见各平台模块测试：`reasoning_effort` 序列化、`thinkingConfig` 透传与 `reasoningContent` 解析均以本地 mock 断言，零真实网络。
