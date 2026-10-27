# 微调（Fine-tuning）

sureai 对模型微调任务做了统一抽象：`FineTuneClient` 接口（上传训练文件 / 创建任务 / 查询任务），
`FineTuneRequest` / `FineTuneResponse` 模型归一，异步任务由调用方轮询 `getFineTune(jobId)` 直至完成。

## 1. 架构概述

```
┌──────────────────────────────────────────────────────────────┐
│              FineTuneClient（接口）                            │
│   String uploadTrainingFile(fileName, byte[] content)         │
│   FineTuneResponse createFineTune(FineTuneRequest req)         │
│   FineTuneResponse getFineTune(String jobId)                   │
└───────────────────────────────┬───────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
  OpenAiCompatClient      AzureFineTuneClient       BaiduFineTuneClient
  /v1/fine_tuning/jobs    /openai/fine_tuning/...    千帆 SFT 端点
  /v1/files               api-key 头                 access_token
```

**三步流程**

1. **上传训练文件**：`uploadTrainingFile(fileName, content)`（OpenAI `purpose=fine-tune`），返回 `trainingFileId`。
2. **创建任务**：`createFineTune(FineTuneRequest)`，返回带 `id` / `status` 的任务。
3. **轮询查询**：`getFineTune(jobId)` 直到 `isCompleted()` / `isFailed()`。

## 2. 快速上手

```java
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;
import com.sure.ai.openai.OpenAiUtil;

// 1) 上传训练集（jsonl 字节）
String fileId = OpenAiUtil.client().uploadTrainingFile("train.jsonl", jsonlBytes);

// 2) 创建微调任务
FineTuneResponse job = OpenAiUtil.client().createFineTune(FineTuneRequest.builder()
    .model("gpt-4o-mini")
    .trainingFileId(fileId)
    .suffix("my-ft")
    .build());

// 3) 轮询状态
while (!job.isCompleted() && !job.isFailed()) {
    Thread.sleep(5000);
    job = OpenAiUtil.client().getFineTune(job.id());
}
System.out.println(job.fineTunedModel());   // 微调产出模型名
```

## 3. 核心 API

### 3.1 FineTuneRequest（Builder）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | String | 是 | 基础模型名 |
| `trainingFileId` | String | 是 | 已上传训练文件 ID |
| `hyperparameters` | Object | 否 | 超参数（对象透传） |
| `suffix` | String | 否 | 产出模型名后缀 |
| `extra` | Map | 否 | 透传平台专属参数 |

### 3.2 FineTuneResponse（record）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 任务 ID |
| `status` | String | 状态（queued/running/succeeded/failed 等） |
| `model` | String | 基础模型 |
| `fineTunedModel` | String | 微调产出模型，未完成时为 `null` |
| `createdAt` / `completedAt` | Long | 时间戳（毫秒），未知/未完成时为 `null` |
| `error` | String | 失败原因，成功时为 `null` |
| `rawJson` | String | 原始响应 JSON |

便捷判断：`isCompleted()`（`succeeded`/`completed`）、`isFailed()`（`failed`/`cancelled`）。

## 4. 各平台端点与状态对比

| 平台 | 端点 | 鉴权 | 状态枚举 |
|---|---|---|---|
| OpenAI | `POST /v1/fine_tuning/jobs`、`GET /v1/fine_tuning/jobs/{id}`、`POST /v1/files`（`purpose=fine-tune`） | Bearer | queued → running → succeeded/failed |
| Azure | `POST /openai/fine_tuning/jobs?api-version=`、`GET 同路径/{id}` | api-key 头 | 同上 |
| 百度千帆 | 千帆 SFT 任务端点 | access_token（自动缓存） | 千帆状态码映射 |
| 通义千问 Qwen / 智谱 / 豆包 | — | 走控制台/Notebook 微调 | 未单独适配（JavaDoc 注明） |

> Qwen / Zhipu / Doubao 的微调目前需在对应控制台或 Notebook 中发起，SDK 暂未封装其微调任务 API。

## 5. 平台对比表

| 平台 | 微调 | 说明 |
|---|---|---|
| OpenAI | ✅ | `OpenAiCompatClient` 内置 |
| Azure OpenAI | ✅ | deployment + api-version |
| 百度千帆 | ✅ | `BaiduFineTuneClient`，状态映射 |
| 通义千问 / 智谱 / 豆包 | ❌ | 控制台微调（未单独适配） |
| 其余平台 | ❌ | 暂未接入 |

微调任务的提交、查询、状态映射与错误解析均以本地 mock 单测断言，零真实网络。
