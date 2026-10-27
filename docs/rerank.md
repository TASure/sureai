# 重排序（Rerank）

sureai 对 RAG 二阶段重排序做了统一抽象：无论平台重排协议如何，对业务代码都暴露同一个
`RerankClient#rerank(RerankRequest)` 同步方法，返回按相关性降序的 `RerankResponse`。

## 1. 架构概述

`RerankClient` 是顶层抽象接口，只有一个核心方法 `rerank(RerankRequest)`。各平台客户端按需实现该接口，
把「请求结构、鉴权、响应解析」封装在内部；core 侧仅定义通用模型，不绑定任何平台。

```
┌─────────────────────────────────────────────────────────────────────┐
│                      RerankClient (接口)                            │
│   RerankResponse rerank(RerankRequest req)                         │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                  ┌─────────────┴─────────────┐
                  │  QwenRerankClient         │
                  │  POST /reranks             │
                  │  (OpenAI 兼容, 通义千问)    │
                  └───────────────────────────┘
                  ┌───────────────────────────┐
                  │  CohereRerankClient       │
                  │  POST /rerank             │
                  │  (Cohere v2 原生协议)      │
                  └───────────────────────────┘
```

**设计要点**

- **同步语义**：重排为单次同步请求，SDK 内部完成协议封装与结果解析，调用方无需感知 HTTP 细节。
- **结果归一**：每条结果映射为 `RerankResult(index, relevanceScore, document, rawJson)`——
  `index` 是命中文档在请求 `documents` 列表中的 0 起下标，`relevanceScore` 越高越相关。
- **RAG 解耦**：重排器通过 `Reranker` 接口接入检索链路，与具体平台 `RerankClient` 解耦。
- **零第三方依赖**：HTTP 基于 `java.net.http.HttpClient`，JSON 为内置轻量实现。

## 2. 快速上手

引入通义千问模块：

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-qwen</artifactId>
    <version>${sureai.version}</version>
</dependency>
```

设置环境变量 `SURE_AI_QWEN_API_KEY` 后，一行重排：

```java
import com.sure.ai.qwen.QwenUtil;
import com.sure.ai.model.RerankResponse;
import com.sure.ai.model.RerankResult;

List<String> docs = List.of(
    "登录路由器管理后台可修改 WiFi 密码。",
    "今天天气晴朗。",
    "长按复位键 10 秒可将路由器恢复出厂设置。"
);

RerankResponse resp = QwenUtil.rerank("如何重置路由器密码？", docs);
for (RerankResult r : resp.results()) {
    System.out.printf("score=%.4f index=%d doc=%s%n",
        r.relevanceScore(), r.index(), r.document());
}
```

> 使用 Cohere 时将依赖换成 `sure-ai-cohere`、环境变量换成 `SURE_AI_COHERE_API_KEY`，
> 调用入口改为 `CohereUtil.rerank(...)`，其余 `RerankRequest` / `RerankResponse` 用法完全一致。

## 3. 核心 API

### 3.1 RerankRequest

Builder 模式，`model` / `query` / `documents` 必填：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | String | 是 | 重排模型 ID |
| `query` | String | 是 | 查询文本 |
| `documents` | List\<String\> | 是 | 候选文档（一阶段召回结果） |
| `topN` | Integer | 否 | 仅返回前 N 条最相关结果 |
| `extra` | Map | 否 | 透传平台专属参数 |

### 3.2 RerankResponse

| 字段 | 类型 | 说明 |
|---|---|---|
| `model` | String | 实际使用的重排模型 |
| `results` | List\<RerankResult\> | 重排结果（按相关性降序） |
| `rawJson` | String | 原始响应 JSON，便于调试与提取扩展字段 |

### 3.3 RerankResult

| 字段 | 类型 | 说明 |
|---|---|---|
| `index` | int | 命中文档在请求 `documents` 列表中的下标（0 起） |
| `relevanceScore` | double | 相关性得分（越高越相关） |
| `document` | String | 命中文档原文 |
| `rawJson` | String | 该条结果原始 JSON |

## 4. 平台接入

### 4.1 通义千问 Qwen（DashScope，OpenAI 兼容 `/reranks`）

- 默认 baseUrl：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- 鉴权：`Authorization: Bearer <apiKey>`
- 环境变量：`SURE_AI_QWEN_API_KEY`（可选 `SURE_AI_QWEN_BASE_URL`）
- 模型常量：`QwenModels.QWEN3_RERANK = "qwen3-rerank"`
- 端点：`POST /reranks`，OpenAI 兼容协议

```java
// 便捷重载（默认 qwen3-rerank）
RerankResponse resp = QwenUtil.rerank(query, documents);

// 全量 Builder（指定 topN / extra）
RerankResponse resp2 = QwenUtil.rerank(RerankRequest.builder()
    .model(QwenModels.QWEN3_RERANK)
    .query(query)
    .documents(documents)
    .topN(3)
    .build());
```

`QwenUtil` 另暴露 `rerankClient()`（取 `QwenRerankClient` 单例）与 `resetRerankClient()`（测试清理）。

### 4.2 Cohere v2（原生 `/rerank` 协议）

- 默认 baseUrl：`https://api.cohere.com/v2`
- 鉴权：`Authorization: Bearer <apiKey>`
- 环境变量：`SURE_AI_COHERE_API_KEY`（可选 `SURE_AI_COHERE_BASE_URL`）
- 模型常量：`CohereModels.RERANK_V3_5 = "rerank-v3.5"`
- 端点：`POST /rerank`，Cohere v2 原生协议（非 OpenAI 兼容）

```xml
<dependency>
    <groupId>io.github.tasure</groupId>
    <artifactId>sure-ai-cohere</artifactId>
    <version>${sureai.version}</version>
</dependency>
```

```java
// 便捷重载（默认 rerank-v3.5）
RerankResponse resp = CohereUtil.rerank(query, documents);

// 全量 Builder（指定 topN / extra）
RerankResponse resp2 = CohereUtil.rerank(RerankRequest.builder()
    .model(CohereModels.RERANK_V3_5)
    .query(query)
    .documents(documents)
    .topN(3)
    .build());
```

`CohereRerankClient` 与 `QwenRerankClient` 实现同一 `RerankClient` SPI，可互换接入 RAG 检索链路。
`CohereUtil` 另暴露 `rerankClient()`（取 `CohereRerankClient` 单例）与 `resetRerankClient()`（测试清理）。

### 4.3 智谱 GLM 未开放说明

智谱 GLM-rerank 目前仅在其**知识库（RAG 平台）内部**提供，未开放独立的公开文本重排 REST API，
因此 sureai 暂未提供 `ZhipuRerankClient`。待智谱开放独立重排接口后将以相同 `RerankClient` 抽象接入。

## 5. RAG 检索链路集成

重排器以二阶段（cross-encoder）精排的角色接入 RAG 检索链路，与一阶段向量召回解耦：

```
查询 ──► 向量召回(VectorRetriever, topK 较粗)
         └─► 重排(Reranker, 按相关性精排, topN 较精)
             └─► 增强生成(RagPipeline)
```

- `Reranker`：RAG 模块的重排抽象接口，方法
  `List<SimilaritySearchResult> rerank(String query, List<SimilaritySearchResult> documents)`。
- `ClientReranker`：把 core 的 `RerankClient`（如 `QwenRerankClient`）适配为 RAG 的 `Reranker`，
  构造时传入重排客户端与模型名。
- `VectorRetriever.Builder.reranker(Reranker)`：在向量检索后追加重排；传 `null` 表示不重排。
- `RagPipeline.Builder.reranker(Reranker)`：在端到端管线中统一注入重排器。

```java
import com.sure.ai.rag.ClientReranker;
import com.sure.ai.rag.pipeline.RagPipeline;
import com.sure.ai.qwen.QwenRerankClient;
import com.sure.ai.qwen.QwenModels;

// 用通义千问重排器包装 RAG 检索结果
Reranker reranker = new ClientReranker(new QwenRerankClient(config), QwenModels.QWEN3_RERANK);

RagPipeline pipeline = RagUtil.pipeline(chatClient, embeddingClient,
        OpenAiModels.GPT_4O_MINI, OpenAiModels.TEXT_EMBEDDING_3_SMALL)
    .reranker(reranker)        // 检索后自动二阶段重排
    .build();
```

> Cohere 接入方式完全一致：`new ClientReranker(new CohereRerankClient(config), CohereModels.RERANK_V3_5)`。

## 6. 平台对比表

| 平台 | 重排端点 | 协议 | 状态 |
|---|---|---|---|
| 通义千问 Qwen | `/reranks` | OpenAI 兼容 | ✅ `QwenRerankClient` |
| Cohere | `/rerank` | Cohere v2 原生 | ✅ `CohereRerankClient` |
| 智谱 GLM | — | 仅知识库内部 | ❌ 未开放独立 API |
| 其他平台 | — | — | ❌ 暂未接入 |

## 7. 测试说明

`QwenRerankClient` 的单元测试使用 JUnit 4 + `com.sun.net.httpserver.HttpServer` 起本地 mock 服务，
**零真实网络请求**：断言请求体 `model/query/documents/top_n` 序列化、Bearer 鉴权头、
以及响应 `results[].index/relevance_score/document` 解析与降序封装。

`CohereRerankClient` 同样使用本地 mock 服务测试，断言请求体 `model/query/documents/top_n`
序列化、Bearer 鉴权头，以及响应 `results[].index/relevance_score/document.text` 解析。

```bash
mvn -B -pl sure-ai-qwen -am test
mvn -B -pl sure-ai-cohere -am test
```
