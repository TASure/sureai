# 内容审核（Moderation）

sureai 对文本内容审核做了统一抽象：`ModerationClient` 接口只有一个 `moderate(ModerationRequest)` 同步方法，
返回 `ModerationResponse`（是否 flagged + 各类别分数）。当前接入 OpenAI / Azure。

## 1. 架构概述

```
┌──────────────────────────────────────────────────────────────┐
│              ModerationClient（接口）                          │
│   ModerationResponse moderate(ModerationRequest request)      │
└───────────────────────────────┬───────────────────────────────┘
                                │
                 ┌──────────────┴──────────────┐
                 │ OpenAiCompatClient          │ AzureModerationClient
                 │ POST /v1/moderations        │ /openai/moderations?api-version=
                 │                             │ api-key 头
```

**设计要点**

- **同步语义**：审核为单次同步请求，SDK 内部完成请求封装与结果解析。
- **结果归一**：`ModerationResult(flagged, categoryScores, categories)`——
  `categoryScores` 为「类别 → 0.0~1.0 分数」，`categories` 为命中的类别集合。

## 2. 快速上手

```java
import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;
import com.sure.ai.openai.OpenAiUtil;

ModerationResponse resp = OpenAiUtil.client()
    .moderate(ModerationRequest.of("今天天气真不错，适合出门散步。"));

System.out.println("flagged=" + resp.flagged());
for (var r : resp.results()) {
    System.out.println(r.flagged() + " " + r.categories());
    r.categoryScores().forEach((k, v) -> System.out.printf("  %s=%.4f%n", k, v));
}
```

## 3. 核心 API

### 3.1 ModerationRequest（Builder / `of(input)`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | String | 否 | 审核模型，缺省 `text-moderation-latest` |
| `input` | String | 是 | 待审核文本 |
| `extra` | Map | 否 | 透传平台专属参数 |

便捷：`ModerationRequest.of(input)`。

### 3.2 ModerationResponse（record）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 审核请求 ID，可能为 `null` |
| `model` | String | 审核模型名 |
| `results` | List\<ModerationResult\> | 审核结果列表 |
| `rawJson` | String | 原始响应 JSON |

便捷：`flagged()`——任一结果 `flagged` 即返回 `true`。

### 3.3 ModerationResult（record）与类别常量

| 字段 | 类型 | 说明 |
|---|---|---|
| `flagged` | boolean | 是否命中违规 |
| `categoryScores` | Map\<String, Double\> | 类别 → 分数（0.0~1.0） |
| `categories` | Set\<String\> | 命中的类别集合 |

内置类别常量：

| 常量 | 值 |
|---|---|
| `ModerationResult.CATEGORY_SEXUAL` | `"sexual"` |
| `CATEGORY_HATE` | `"hate"` |
| `CATEGORY_HARASSMENT` | `"harassment"` |
| `CATEGORY_SELF_HARM` | `"self-harm"` |
| `CATEGORY_VIOLENCE` | `"violence"` |

## 4. 平台配置

| 平台 | 端点 | 鉴权 | 环境变量 |
|---|---|---|---|
| OpenAI | `POST /v1/moderations` | Bearer | `SURE_AI_OPENAI_API_KEY` |
| Azure | `POST /openai/moderations?api-version=` | api-key 头 | `SURE_AI_AZURE_API_KEY` |

## 5. 平台对比表

| 平台 | 内容审核 | 说明 |
|---|---|---|
| OpenAI | ✅ | `OpenAiCompatClient` 内置 |
| Azure OpenAI | ✅ | `AzureModerationClient` |
| 其余平台 | ❌ | 暂未接入 |

请求序列化与 `flagged`/`category_scores`/`categories` 解析均以本地 mock 单测断言，零真实网络。
