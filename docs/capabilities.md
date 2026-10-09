<!--
  ~ Copyright (c) 2026 sureai contributors
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
-->

# 平台能力面收口（Capability Guard）

> 自 **v1.4.0** 起，sureai 引入 `Capability` 枚举与 `guard()` 机制，对「平台 Client 是否支持某能力」做**发请求前的快速失败（fail-fast）**。本文档描述这一机制的行为、异常形态与各平台的能力声明清单。

## 设计说明

在 v1.4.0 之前，一个平台 Client（例如 DeepSeek）在调用它并不具备的能力接口（例如 `embed()`）时，SDK 会把请求照常发往上游，再由上游返回一个含糊的 `400 Bad Request` / `404 Not Found`。用户看到的只是一个 HTTP 4xx，很难第一时间判断「是我参数错了，还是这个平台压根不支持这个能力」。

v1.4.0 对此做了收口：

- `com.sure.ai.client.Capability` 是一个枚举，枚举值即「一种可被调用的能力」。
- 每个平台 Client 通过覆写 `AbstractAiClient#capabilities()` 声明**自身实际支持的能力集合**（一个不可变 `Set<Capability>`）。
- 基类 `AbstractAiClient#guard(Capability)` 是 `final` 终态方法：在「部分平台不支持」的能力方法入口处被调用；若当前 Client 未声明支持该能力，则**在组装 HTTP 请求之前**立即抛出 `AiException`，根本不会发出网络请求。
- 对已声明支持的能力，`guard()` 是零开销空操作（一次 `Set.contains` 判断）。

## 异常消息形态

`guard()` 抛出的异常消息格式固定为：

```
<平台 slug> does not support <CAPABILITY> capability
```

其中 `<平台 slug>` 来自 Client 的 `name()`（见下表），`<CAPABILITY>` 是枚举常量名（大写）。例如，在只声明了对话能力的 DeepSeek Client 上调用 `embed()`：

```java
DeepSeekClient client = new DeepSeekClient(...);
client.embed(EmbeddingRequest.of("text-embedding-2", "hello"));
// 抛出 AiException: deepseek does not support EMBED capability
```

再如在 Grok Client 上发起图像生成：

```text
grok does not support IMAGE capability
```

## 与旧行为的差异

| | v1.4.0 之前 | v1.4.0 起 |
|---|---|---|
| 失败时机 | 请求已发往上游，等网络往返后 | **发请求前**，进程内立即抛出 |
| 失败信号 | 上游 4xx（400/404），报文含糊 | `AiException`，消息直接点名平台 + 能力 |
| 配额影响 | 消耗了一次（可能计费的）请求 | 不消耗任何请求配额 |
| 用户认知 | 困惑：参数错？鉴权错？平台不支持？ | 明确：`deepseek does not support EMBED capability` |

## 为什么这样设计

1. **节省请求配额**：在不支持 Embedding 的平台上误调 `embed()`，旧行为会白白打一次上游、可能触发限流；新行为在本地即失败。
2. **错误反馈清晰**：异常消息直接告诉你「哪个平台」+「缺哪个能力」，无需再翻文档对照。
3. **语义统一、不可覆写**：`guard()` 是 `final`，子类无法绕过或改写其语义，保证所有平台的快速失败行为一致。

## Capability 枚举值

`com.sure.ai.client.Capability` 当前定义了 14 个枚举值：

| 枚举值 | 含义 |
|--------|------|
| `CHAT` | 同步对话（chat/completions） |
| `CHAT_STREAM` | 流式对话（SSE chat/completions） |
| `EMBED` | 向量嵌入（embeddings） |
| `IMAGE` | 图像生成（images/generations） |
| `VIDEO` | 视频生成（异步任务） |
| `TTS` | 语音合成（TTS） |
| `STT` | 语音识别（STT / 转录） |
| `RERANK` | 重排（rerank） |
| `BATCH` | 批处理（batch 异步任务） |
| `MODERATION` | 内容审核（moderations） |
| `FINETUNE` | 微调（fine-tuning 任务 + 训练文件上传） |
| `REALTIME` | Realtime（实时音频 / 事件流） |
| `FUNCTION_CALLING` | 函数调用（对话模型能力） |
| `TOOL_CALLING` | 工具调用（对话模型能力） |

> 说明：`CHAT` 与 `CHAT_STREAM` 是所有兼容平台都具备的核心能力，基类**不对其加 guard**——任何 Client 都应当能对话。当前 `guard()` 实际挂在 `EMBED` / `IMAGE` / `VIDEO` / `MODERATION` / `FINETUNE` 这五类「部分平台不支持」的能力方法入口上。

## 各平台能力声明清单

以下清单逐字核对自各 Client 源码的 `capabilities()` 方法。

### 显式声明能力集的平台

自 **v1.9.0** 起，OpenAI / Azure / 豆包 / 通义千问 / 智谱也从「继承兼容基类全量兜底」改为**显式覆写 `capabilities()`**，只声明本平台真实可用的能力；v1.9.0 新增的 7 个 OpenAI 兼容平台（MiniMax / StepFun / Baichuan / Lingyi / SiliconFlow / Hunyuan / Spark）同样在模块内显式收窄。下表逐平台列出「声明支持」与「调用会立即抛 `AiException`」的能力。受 guard 保护的能力共 5 个：`EMBED` / `IMAGE` / `VIDEO` / `MODERATION` / `FINETUNE`。

| 平台 slug | Client | `name()` | 声明支持的能力 | 调用即快速失败的能力 |
|-----------|--------|----------|----------------|----------------------|
| `openai` | `OpenAiClient` | `"openai"` | `CHAT`, `CHAT_STREAM`, `EMBED`, `IMAGE`, `VIDEO`, `TTS`, `STT`, `MODERATION`, `FINETUNE` | （无，沿用全量） |
| `azure` | `AzureClient` | `"azure"` | `CHAT`, `CHAT_STREAM`, `EMBED`, `IMAGE`, `MODERATION`, `FINETUNE` | `VIDEO` |
| `doubao` | `DoubaoClient` | `"doubao"` | `CHAT`, `CHAT_STREAM`, `EMBED`, `IMAGE` | `VIDEO`, `MODERATION`, `FINETUNE` |
| `qwen` | `QwenClient` | `"qwen"` | `CHAT`, `CHAT_STREAM`, `EMBED`, `TTS`, `STT` | `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `zhipu` | `ZhipuClient` | `"zhipu"` | `CHAT`, `CHAT_STREAM`, `EMBED`, `IMAGE`, `TTS`, `STT` | `VIDEO`, `MODERATION`, `FINETUNE` |
| `deepseek` | `DeepSeekClient` | `"deepseek"` | `CHAT`, `CHAT_STREAM` | `EMBED`, `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `grok` | `GrokClient` | `"grok"` | `CHAT`, `CHAT_STREAM` | `EMBED`, `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `mistral` | `MistralClient` | `"mistral"` | `CHAT`, `CHAT_STREAM`, `EMBED`, `FINETUNE` | `IMAGE`, `VIDEO`, `MODERATION` |
| `llamacpp` | `LlamaCppClient` | `"llamacpp"` | `CHAT`, `CHAT_STREAM`, `EMBED` | `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `moonshot` | `MoonshotClient` | `"moonshot"` | `CHAT`, `CHAT_STREAM`, `EMBED` | `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `minimax` | `MiniMaxClient` | `"minimax"` | `CHAT`, `CHAT_STREAM` | `EMBED`, `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `stepfun` | `StepFunClient` | `"stepfun"` | `CHAT`, `CHAT_STREAM` | `EMBED`, `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `baichuan` | `BaichuanClient` | `"baichuan"` | `CHAT`, `CHAT_STREAM` | `EMBED`, `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `lingyi` | `LingyiClient` | `"lingyi"` | `CHAT`, `CHAT_STREAM` | `EMBED`, `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `spark` | `SparkClient` | `"spark"` | `CHAT`, `CHAT_STREAM` | `EMBED`, `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `siliconflow` | `SiliconFlowClient` | `"siliconflow"` | `CHAT`, `CHAT_STREAM`, `EMBED` | `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |
| `hunyuan` | `HunyuanClient` | `"hunyuan"` | `CHAT`, `CHAT_STREAM`, `EMBED` | `IMAGE`, `VIDEO`, `MODERATION`, `FINETUNE` |

> 说明：`OpenAiCompatClient` 的 `capabilities()` 默认仍声明全量（保守兜底），供未覆写的兼容平台沿用；上表中各家均已显式覆写为上表集合。豆包 / 通义 / 智谱的兼容面经官方文档核实**无 `/moderations` 端点**，v1.9.0 已从其 `capabilities()` 移除 `MODERATION`（OpenAI / Azure 保留）；对这三家调用 `moderate()` 现由 `guard(Capability.MODERATION)` 在发请求前快速失败。

### 范围说明

- `RERANK` / `BATCH` / `REALTIME` 等能力由独立的专用 Client 类（如 `QwenRerankClient`、`OpenAiBatchClient`、`OpenAiRealtimeClient`）承载，这些类直接继承 `AbstractAiClient`，不在 OpenAI 兼容基类的 guard 表面之内。
- `Anthropic` / `Gemini` / `Bedrock` / `Cohere` 的主 Client 直接继承 `AbstractAiClient`（而非 `OpenAiCompatClient`），其对话/嵌入等方法各自独立实现，不经过兼容基类的 guard 入口。
- TTS / STT 虽列入基类声明集合，但兼容基类的 `synthesize()` / `transcribe()` 直接委托音频策略、未挂 guard；具体 TTS/STT 能力由各平台专用 Client（如 `AzureTtsClient`、`DoubaoTtsClient`）实现。

> **注意：本表的 `IMAGE` / `VIDEO` 指「图像/视频生成」能力（受 guard 的生成接口），与多模态「输入」（图像理解 / 视频理解）是两回事。**
> 各平台的图像/视频**输入**支持情况（Anthropic 视觉、Gemini/Qwen-VL 视频输入、工具结果回传语义）
> 不在本表范围，已在 v2.4.0 批次按官方文档联网核实并记录，详见
> [multimodal.md](multimodal.md) 与各平台文档的「能力核实」注记。本批未改动任何平台的生成能力声明。

## 演进约定

后续新增平台时，应在其 Client 中覆写 `capabilities()`，**只声明真实可用的能力**，而非一律继承全量兜底；这样未支持的能力会在发请求前被 `guard()` 清晰拦截，而不是把含糊的 4xx 抛给用户。

## 编排能力（v2.5.0，非平台 guard 维）

本文的 `Capability` 枚举矩阵描述的是「某平台 Client 是否支持某**请求能力**」（CHAT / EMBED / IMAGE …），由 `guard()` 在发请求前快速失败。它与 **v2.5.0 新增的声明式编排层**是两回事——后者是 core 之上的应用侧编排能力，**不挂在任何平台 Client 的 guard 表面上**，因而不在本矩阵内：

- **AiService 接口即服务**（`sure-ai-framework`）：注解族 + JDK 动态代理把接口映射为对话服务，任意 `AiClient` 均可驱动；
- **Advisor 链**：`before`/`around`/`after` 三钩子横切，四件套（语义缓存短路 / 日志 / 工具循环 / 结构化自纠）；
- **SemanticCache**：embedding 余弦阈值命中，可插拔 `CacheStore`。

编排能力与平台无关——同一套接口与 Advisor 链换平台 client 即可复用。详见 [framework.md](framework.md)。

## 文档导入与向量库适配（v2.6.0，非平台 guard 维）

与 v2.5.0 编排层同理，v2.6.0 的「文档导入器」与「5 个新向量库」都是**平台无关的应用侧能力**，
不挂在任何平台 Client 的 guard 表面上，因而不在上面的 `Capability` 矩阵内：

- **文档导入器（`sure-ai-ingest`）**：`DocumentLoader` SPI（`FileSystemLoader` 本地 /
  `URLLoader` 网络）+ `DocumentParser` 格式解析 SPI；内置 TXT/MD/HTML/PDF 为纯 JDK 实现
  （PDF 为有限文本层提取、不引 PDFBox；HTML 为最小正则剥标签），DOCX/XLSX/PPTX 由可选
  `sure-ai-ingest-poi`（Apache POI 5.5.1 provided）承载。产出复用 rag `Document`。详见 [ingest.md](ingest.md)。
- **向量库 9 → 14**：新增 `PgVectorStore`（PG 前端/后端协议）、`TypesenseVectorStore`（REST）、
  `CassandraVectorStore`（CQL v4）、`MongoDbVectorStore`（OP_MSG+BSON）、`Neo4jVectorStore`
  （HTTP tx/commit），均为 JDK 原生协议客户端、零官方驱动；各家鉴权与查询能力的支持子集
  （如 PG 仅 trust/明文、Cassandra 仅无认证/SASL PLAIN、MongoDB 未实现 SCRAM、Neo4j 走 HTTP 而非 Bolt）
  在 [vector-stores.md](vector-stores.md) 逐库如实标注。配套新增 5 个 metadata filter 方言翻译器
  （6 → 11）。

这些能力与「某平台是否支持某请求能力」正交：导入与向量存储换实现不影响平台 guard 行为。
