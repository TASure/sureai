# 模型列表（Models）

sureai 对「列出账号可用模型」做了统一抽象：`ModelsClient` 接口只有一个 `listModels()` 方法，
返回归一后的 `Model` 记录。当前接入 OpenAI / Azure / Gemini / Anthropic / Qwen。

## 1. 架构概述

```
┌──────────────────────────────────────────────────────────────┐
│              ModelsClient（接口）                             │
│   List<Model> listModels()                                   │
└───────────────────────────────┬───────────────────────────────┘
                                │
   ┌──────────────┬───────────┼────────────┬─────────────────┐
OpenAiCompat   Azure        Gemini      Anthropic           Qwen(兼容)
/v1/models     /openai/     /v1beta/    /v1/models         compatible-mode
               models?api-  models[]     data[].id         /v1/models
               version=     .name 去前缀
```

**设计要点**

- **结果归一**：各平台响应结构不同（OpenAI `data[].id`、Gemini `models[].name`、Anthropic `data[].id`），
  统一映射为 `Model(id, created, ownedBy, object, rawJson)`。
- **不支持即显式抛错**：无公开模型列表 REST API 的平台（智谱 / 豆包），`listModels()` 直接抛
  `AiException` 并注明，而非静默返回空列表。

## 2. 快速上手

```java
import com.sure.ai.model.Model;
import com.sure.ai.openai.OpenAiUtil;

for (Model m : OpenAiUtil.client().listModels()) {
    System.out.println(m.id() + "  owned_by=" + m.ownedBy() + "  created=" + m.created());
}
```

## 3. 核心模型 Model（record）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 模型 ID |
| `created` | Long | 创建时间戳（秒），未知为 `null` |
| `ownedBy` | String | 提供方 / 所有者，可能为 `null` |
| `object` | String | 对象类型（通常 `"model"`），可能为 `null` |
| `rawJson` | String | 原始 JSON，便于提取扩展字段 |

## 4. 各平台端点

| 平台 | 端点 | 解析方式 |
|---|---|---|
| OpenAI | `GET /v1/models` | `data[].id` / `owned_by` / `created` |
| Azure | `GET /openai/models?api-version=` | 同 OpenAI 结构 |
| Google Gemini | `GET /v1beta/models` | `models[].name` 去掉 `models/` 前缀 |
| Anthropic | `GET /v1/models` | `data[].id` |
| 通义千问 Qwen | `GET /compatible-mode/v1/models` | 继承 core（OpenAI 兼容） |
| 智谱 GLM / 豆包 | — | 无公开 REST API，`listModels()` 抛 `AiException` |

## 5. 平台对比表

| 平台 | 模型列表 | 说明 |
|---|---|---|
| OpenAI | ✅ | `OpenAiCompatClient` 内置 |
| Azure OpenAI | ✅ | api-version 查询参数 |
| Google Gemini | ✅ | `models[].name` 去前缀 |
| Anthropic | ✅ | `data[].id` |
| 通义千问 Qwen | ✅ | 兼容模式 |
| 智谱 GLM / 豆包 | ❌ | 无公开 REST API（抛 `AiException`） |
| 其余平台 | ❌ | 暂未接入 |

各平台模型列表的端点调用与响应解析均以本地 mock 单测断言，零真实网络。
