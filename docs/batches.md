# 批处理（Batches）

sureai 对平台的批量异步推理任务做了统一抽象：`BatchClient` 只暴露「提交 / 查询」两个原子操作，
大批量低优先级推理（离线标注、批量摘要、评测集跑数）可用 50% 折扣异步完成，SDK 屏蔽各家协议差异。

## 1. 架构概述

```
┌─────────────────────────────────────────────────────────────────────┐
│                    BatchClient (接口)                                │
│   BatchResponse createBatch(BatchRequest req)                      │
│   BatchResponse getBatch(String batchId)                            │
└───────────────┬───────────────────────┬─────────────────────────────┘
                │                       │
   ┌────────────┴───────────┐   ┌────────┴─────────┐
   │ OpenAI 协议族          │   │ Anthropic 协议族  │
   │  OpenAiBatchClient     │   │ AnthropicBatch   │
   │  AzureBatchClient      │   │  requests 内联 + │
   │  ZhipuBatchClient     │   │  custom_id       │
   │  (文件上传 + /batches) │   │  /v1/messages/   │
   │                        │   │  batches         │
   └────────────────────────┘   └──────────────────┘
```

**设计要点**

- **异步语义**：`createBatch` 提交后立即返回带 `id` 的初始 `BatchResponse`，调用方按 `id` 轮询
  `getBatch` 直至终态。
- **两族协议**：OpenAI 协议族以「先上传 JSONL 文件 → 引用 `input_file_id` 建 batch」为输入；
  Anthropic 协议族直接在请求体内联 `requests`（每条带 `custom_id`）。SDK 在各自客户端内消化差异。
- **轮询便捷方法**：`OpenAiBatchClient.waitForCompletion(batchId, timeoutMs)` 以 2s 间隔自动轮询，
  同步阻塞直至 `completed` / `failed` / 超时。

## 2. 快速上手

引入 OpenAI 模块：

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-openai</artifactId>
    <version>${sureai.version}</version>
</dependency>
```

> 前置：Batches 以已上传的 JSONL 文件为输入，须先通过平台文件上传接口取得 `input_file_id`。
> JSONL 每行形如：
> `{"custom_id":"req-1","method":"POST","url":"/v1/chat/completions","body":{"model":"gpt-4o-mini","messages":[{"role":"user","content":"..."}]}}`

```java
import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;
import com.sure.ai.openai.OpenAiUtil;

// 1. 提交任务
BatchRequest req = BatchRequest.builder()
    .model("gpt-4o-mini")
    .inputFileId("batch_xxx_file_id")   // 已上传 JSONL 的 file id
    .completionWindow("24h")
    .build();
BatchResponse created = OpenAiUtil.batch(req);
System.out.println("id=" + created.id() + " status=" + created.status());

// 2. 轮询直至终态（便捷方法，2s 间隔）
BatchResponse done = OpenAiUtil.batchClient()
    .waitForCompletion(created.id(), 60 * 60 * 1000L);
BatchResponse.RequestCounts c = done.requestCounts();
System.out.println("total=" + c.total() + " completed=" + c.completed() + " failed=" + c.failed());
```

也可用 `OpenAiUtil.getBatch(batchId)` 主动查询一次状态，自行实现轮询循环。

## 3. 核心 API

### 3.1 BatchRequest

Builder 模式，`model` 必填；`inputFileId` 与 `requests` 至少提供其一：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | String | 是 | 批处理模型 ID |
| `inputFileId` | String | 二选一 | 已上传 JSONL 文件 ID（OpenAI 协议族） |
| `requests` | List\<ChatRequest\> | 二选一 | 直接传入一组对话请求（Anthropic 协议族内联） |
| `completionWindow` | String | 否 | 完成时间窗，如 `"24h"` |
| `metadata` | Map | 否 | 自定义元数据 |
| `extra` | Map | 否 | 透传平台专属参数 |

### 3.2 BatchResponse

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 任务 ID |
| `status` | String | 状态：validating / in_progress / finalizing / completed / failed / cancelled / expired |
| `createdAt` | long | 创建时间戳（Unix 秒，未知为 0） |
| `completedAt` | long | 完成时间戳（未完成或未知为 0） |
| `requestCounts` | RequestCounts | `(total, completed, failed)` |
| `error` | String | 失败原因（成功为 null） |
| `rawJson` | String | 原始响应 JSON |

便捷判断：`isCompleted()`、`isFailed()`。

## 4. 各平台接入

### 4.1 OpenAI

- 端点：`POST /v1/batches` 创建，`GET /v1/batches/{id}` 查询
- 鉴权：`Authorization: Bearer <apiKey>`
- 环境变量：`SURE_AI_OPENAI_API_KEY`
- 输入：`input_file_id`（先上传 JSONL）
- 便捷：`OpenAiUtil.batchClient().waitForCompletion(batchId, timeoutMs)`

```java
BatchResponse r = OpenAiUtil.batch(req);
BatchResponse s = OpenAiUtil.getBatch(r.id());
```

### 4.2 Azure OpenAI

- 端点：`/openai/v1/batches?api-version=...`
- 鉴权：`api-key` 头
- 环境变量：`SURE_AI_AZURE_API_KEY` / `SURE_AI_AZURE_BASE_URL`
- 协议同 OpenAI：`AzureUtil.batch(...)` / `getBatch(...)`。

### 4.3 智谱 Zhipu

- 端点：`/batches`
- 鉴权：JWT（HS256，`id.secret` 格式签发）
- 环境变量：`SURE_AI_ZHIPU_API_KEY`
- 协议同 OpenAI：`ZhipuUtil.batch(...)` / `getBatch(...)`。

### 4.4 Anthropic（不同协议）

- 端点：`POST /v1/messages/batches` 创建，`GET /v1/messages/batches/{id}` 查询
- 鉴权：`x-api-key` 头 + `anthropic-version`
- 输入：**请求体内联 `requests`**，每条带 `custom_id`（而非先上传文件）；`ChatRequest` 列表由 SDK 封装为内联结构
- 状态字段：`processing_status`

```java
BatchRequest req = BatchRequest.builder()
    .model("claude-3-5-sonnet")
    .addRequest(ChatRequest.builder()
        .model("claude-3-5-sonnet")
        .messages(List.of(ChatMessage.user("一句话总结 Java。")))
        .build())
    .build();
BatchResponse r = AnthropicUtil.batch(req);
```

各平台 `XxxUtil` 均新增 `batchClient()` / `batch(BatchRequest)` / `getBatch(id)` / `resetBatchClient()`。

## 5. 异步轮询说明

Batches 为**长时异步任务**（通常数分钟到 24 小时内完成），SDK 不强行同步等待：

- **自行轮询**：循环调用 `getBatch(id)`，判断 `isCompleted()` / `isFailed()`，自行决定间隔与超时；
- **便捷阻塞**：OpenAI 协议族的 `waitForCompletion(batchId, timeoutMs)` 内部以 **2000ms** 间隔轮询，
  直至终态或超时（超时抛 `AiTimeoutException`，可中断）。

终态之外的中间态（validating / in_progress / finalizing）一律视为「进行中」，继续轮询。

## 6. 平台对比表

| 平台 | 端点 | 输入形式 | 鉴权 | 状态字段 |
|---|---|---|---|---|
| OpenAI | `/v1/batches` | `input_file_id` | Bearer | `status` |
| Azure | `/openai/v1/batches` | `input_file_id` | api-key 头 | `status` |
| 智谱 Zhipu | `/batches` | `input_file_id` | JWT | `status` |
| Anthropic | `/v1/messages/batches` | 内联 `requests` + `custom_id` | x-api-key 头 | `processing_status` |

## 7. 测试说明

各批处理客户端的单元测试用本地 `HttpServer` mock 两段式响应（创建返回 id → 查询返回进行中 → 再次查询 completed），
断言：请求路径与鉴权头（Bearer / api-key / JWT / x-api-key）、`completion_window`、`request_counts` 解析、
以及轮询间隔。Anthropic 用例额外断言内联 `requests` 与 `custom_id` 封装。

```bash
mvn -B -pl sure-ai-openai -am test
```
