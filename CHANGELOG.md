# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **RAG 生产化——外部向量库适配**：`sure-ai-rag` 新增 `MilvusVectorStore`（Milvus 2.x REST v2，`/v2/vectordb/entities/insert|search|delete`，Bearer 鉴权，COSINE/L2/IP 距离映射）与 `ChromaVectorStore`（Chroma REST v1，`/api/v1/collections/{id}/add|query|delete`，X-Chroma-Token，构造时 get-or-create），均 `implements VectorStore`，JDK HttpClient 零第三方依赖，本地 HttpServer mock 测试 9 个。pgvector 以文档可复制 JDBC 示例提供（不引驱动）。文档 `docs/vector-stores.md`。
- **RAG 生产化——Prompt 模板与查询改写**：新包 `com.sure.ai.rag.prompt`——`PromptTemplate`（`{var}`/`{var=default}` 占位符、严格模式、fromResource、varargs render）、`ChatTemplate`（多消息模板 + few-shot 示例组装）；新包 `com.sure.ai.rag.rewriter`——`QueryRewriter` 接口 + `ModelQueryRewriter`（AiClient 生成多查询，按行解析，异常回退原查询），不注入 RagPipeline 默认链路（向后兼容），文档说明手动融合方式。新增 33 个测试，rag 行覆盖率 88.4%。文档 `docs/prompt-template.md`。
- **响应缓存（core）**：`com.sure.ai.client.cache` 包——`CacheStore` SPI（get/put/remove/clear，TTL）、`ChatCacheKey` 请求归一化 SHA-256（参与字段：model/messages/temperature/topP/tools/responseFormat/reasoningEffort/thinkingConfig/grounding；不含 maxTokens/n/stream/extraHeaders，tools 按 name 排序保证顺序无关）、`LruCacheStore` 内置实现（LinkedHashMap accessOrder + synchronized，容量/TTL 可配，惰性过期）。`AiConfig` 新增 `cacheStore`/`cacheTtl`（默认 null=关闭，零开销）。`OpenAiCompatClient.chat()` 非流式请求查缓存，命中直接返回（不触发网络/指标/重试），未命中走原逻辑并回写；流式/错误不缓存。新增 19 个测试，core 行覆盖率 84.2%。文档 `docs/cache.md`（含 Redis CacheStore 可复制示例）。
- **Spring Boot Starter**：新模块 `sure-ai-spring-boot-starter`（`com.sure.ai.boot` 包）——`SureAiProperties`（`@ConfigurationProperties(prefix="sure.ai")`，11 平台嵌套属性 + rag/agent 预留）、`SureAiAutoConfiguration`（`@AutoConfiguration`，11 平台 `@Bean` + `@ConditionalOnMissingBean` + `@ConditionalOnProperty(name="api-key")`，缺 key 不装配）、`AutoConfiguration.imports` 注册。`spring-boot-autoconfigure` 仅本模块 compile，不进 sure-ai-all、不进运行期依赖链，core/平台模块零 Spring 依赖。5 个 `ApplicationContextRunner` 测试。文档 `docs/spring-boot.md`。
- 开发治理：引入「软件研发小组」团队配置（`.team/`：manifest + team.yml + a1..a5 角色定义），新增 `docs/TEAM.md` 说明协作模式与迭代角色链（统筹 → 需求/验收 → 架构 → 实现 → 独立质检），README 中英文同步。

## [1.0.0] - 2026-09-17

### Added
- 可观测性与重试（横切 core）——`com.sure.ai.client.observability`：
  - `RetryListener` 重试事件回调（`onRetry`/`onRetryExhausted`，全 default 空实现，listener 异常被吞仅记 warning，不影响主流程）。
  - `MetricsCollector` 指标 SPI（`onRequestStart`/`onRequestSuccess`/`onRequestFailure`/`onRetry`/`onTokenUsage`），未挂载零开销。
  - `AiMetrics` 内置零依赖线程安全实现（请求/成功/失败/重试计数、状态码计数、耗时五桶直方图、Token 累计），`snapshot()` 不可变快照 + `reset()` 清零。
  - `AbstractAiClient` 重试逻辑统一重构：`doPostRaw`/`doPostStream`/`doGetRaw`/`doPostBinary`/`doPostMultipart` 五个发送路径共用 `executeWithRetry` 模板，重试语义（429/5xx、Retry-After、指数退避 1s/2s/4s、maxRetries）保持不变；`OpenAiCompatClient` 在 chat/embedding 解析到 usage 时回调 `onTokenUsage`。
  - `AiConfig` 扩展：`retryListener`/`retryListeners`（可累加）、`metricsCollector`、`rateLimitQps`（>0 启用 sure-core `RateLimiter` 令牌桶限流，每次 HTTP 发送前含重试 acquire，默认 0 关闭）。
- `sure-ai-micrometer` 新模块：`MicrometerMetricsAdapter implements MetricsCollector`，`micrometer-core` 以 `provided` 引入不传递，映射为 Micrometer Counter/Timer。
- 工程集成：父 POM 收录 `sure-ai-micrometer`（位于 `sure-ai-agent` 之后、`sure-ai-openai` 之前），SpotBugs 新增带中文理由的排除（`AiMetrics.Snapshot` 不可变快照、`MicrometerMetricsAdapter` 共享 MeterRegistry）。
- 文档：新增 `docs/observability.md`（重试语义、RetryListener/MetricsCollector/AiMetrics 用法、限流、Micrometer 桥接、完整配置示例），README 中英文特性与模块表同步。
- `sure-ai-agent`：Agent 编排能力模块（仅依赖 sure-ai-core，与平台解耦，任意 `AiClient` 可驱动）：
  - 工具注册中心 `ToolRegistry`（`ToolHandler` 函数式接口、`ToolExecutionResult` 成功/失败封装，线程安全，同名覆盖）。
  - `ToolArgumentValidator`：按 JSON Schema 做 required + 基础类型校验（string/integer/number/boolean/array/object，简化范围，不含 pattern/enum）。
  - `ReActAgent`：Thought → Action → Observation 多工具循环，参数校验/工具未注册/执行异常均回灌模型自我修正，`maxIterations`（默认 10）+ 总 `timeout`（默认 120s）双防护，注册中心为空退化为单次 chat。
  - `AgentListener` 事件回调（onThought/onToolCall/onToolResult/onFinish/onError，全 default 空实现）、`AgentUtil` 全局注册中心静态入口（双检锁单例）、`PlanExecuteAgent` 骨架（未实现，run 抛 UnsupportedOperationException）。
- 工程集成：父 POM / `sure-ai-bom` / `sure-ai-all` 收录 `sure-ai-agent`，examples 新增 `AgentDemo`（离线 Fake 模型演示天气查询 + 计算器多工具编排，零真实网络），`ExamplesRunner` 新增 `agent` case。
- 文档：新增 `docs/agent.md`（ReAct 原理、工具注册、参数校验、事件回调、离线示例、平台兼容性、防护表、Plan-and-Execute 状态），README 中英文特性与模块表同步。
- `sure-ai-core`：图像生成抽象层——`ImageClient` 接口、`ImageRequest`/`ImageResponse`/`ImageResult` 通用模型，`AbstractAiClient` 新增 `doGet` 用于异步轮询，`OpenAiCompatClient` 内置 `/images/generations` 同步协议实现。
- 6 个平台图像生成接入：
  - `sure-ai-openai`：DALL·E 3 / DALL·E 2，同步 OpenAI 图像协议。
  - `sure-ai-azure`：Azure OpenAI DALL·E 3，deployment 路径 + api-version 查询参数。
  - `sure-ai-qwen`：通义万相（wanx-v1 / wan2.1），原生 DashScope 异步任务（提交 → 轮询 → 结果），`X-DashScope-Async: enable` 头。
  - `sure-ai-zhipu`：CogView 3/4 / GLM-Image，OpenAI 兼容图像协议。
  - `sure-ai-baidu`：文心一格 ERNIE-ViLG v2，异步任务轮询，复用 access_token 缓存机制。
  - `sure-ai-gemini`：Gemini 图像生成（generateContent + responseModalities），同步返回 Base64 图像数据。
- 各平台 `XxxUtil` 新增 `image(model, prompt)` / `image(ImageRequest)` 便捷入口与 `imageClient()` 单例（异步平台）。
- 工程集成：examples 新增 `ImageDemo`（多平台图像生成演示，缺 Key 优雅跳过），`ExamplesRunner` 新增 `image` case。
- 文档：新增 `docs/images.md`（架构图、快速上手、各平台配置、异步轮询说明、平台对比表）。
- `sure-ai-rag`：RAG（检索增强生成）能力模块，仅依赖 sure-ai-core，与具体平台解耦：
  - 文本分块：`TextSplitter` 接口 + 递归字符分块器（块大小/重叠/分隔符优先级，参考 LangChain 思路的纯 JDK 实现）。
  - 向量存储：`VectorStore` 抽象 + 进程内实现 `InMemoryVectorStore`（余弦相似度、topK、相似度阈值），可实现接口接入 Milvus/FAISS/pgvector 等外部向量库。
  - 向量化：`EmbeddingProvider` 抽象 + `ClientEmbeddingProvider` 适配 sure-ai-core 的 `EmbeddingClient`，任意平台客户端可直接组合。
  - 检索：`Retriever` 抽象 + `VectorRetriever`（查询向量化 → 相似度检索 → 文档化，支持最低相似度过滤）。
  - 管线：`RagPipeline` 端到端编排（索引 → 检索 → 增强 → 生成），支持自定义分块器/向量库/检索器/系统提示词模板/默认 topK。
  - 静态入口 `RagUtil`：splitter() / inMemoryStore() / pipeline(...) 一行创建。
- 工程集成：父 POM 与 `sure-ai-bom` 收录 `sure-ai-rag`，`sure-ai-all` 聚合引入，examples 新增 `RagDemo`（基于 OpenAI，缺 Key 自动跳过）。
- 文档：新增 `docs/rag.md`（架构图、快速上手、自定义配置、外部向量库接入、平台支持与对比）。
- `sure-ai-rag`：文档加载器——`DocumentLoader` 抽象 + `TxtDocumentLoader`（本地文件/输入流/字符串，UTF-8 可指定字符集）+ `UrlDocumentLoader`（JDK HttpClient GET，连接/请求超时可配，基础 HTML 去标签 + 实体解码，非 2xx 抛异常）。
- `sure-ai-rag`：混合检索——`KeywordRetriever`（BM25 纯 JDK 实现，k1=1.2/b=0.75 可配，长度归一化）+ `HybridRetriever`（向量 + 关键词两路召回 topK*2，min-max 归一化后按可配置权重加权融合）。
- `sure-ai-rag`：分块策略扩展——`MarkdownTextSplitter`（按标题层级分节、保留标题路径上下文，长节按段落+大小二次切分）+ `FixedSizeTextSplitter`（固定字符大小滑动窗口 + 重叠）。
- 文档：扩展 `docs/rag.md`（文档加载器、混合检索、分块策略对比与示例），README 中英文 RAG 段落同步更新。
- `sure-ai-core`：视频生成抽象层——`VideoClient` 接口、`VideoRequest`/`VideoResponse`/`VideoResult` 通用模型，全平台异步任务轮询（提交→轮询→结果）屏蔽，对外同步返回。
- `sure-ai-core`：语音抽象层——`AudioClient` 接口（TTS synthesize + STT transcribe）、`TtsRequest`/`TtsResponse`/`SttRequest`/`SttResponse`/`Segment`/`Word` 模型；`AbstractAiClient` 新增 `doPostBinary`（二进制响应，TTS）与 `doPostMultipart`（multipart/form-data 上传，STT），零第三方依赖。
- 5 个平台视频生成接入（全异步轮询）：
  - `sure-ai-openai`：Sora 2 / Sora 2 Pro，POST /v1/videos → GET /v1/videos/{id}（注意：Sora API 官方标注 2026-09-24 关闭）。
  - `sure-ai-qwen`：通义万相 Wan 2.6/2.5，DashScope 异步任务（X-DashScope-Async 头），轮询 /api/v1/tasks/{id}。
  - `sure-ai-zhipu`：CogVideoX 3/2，POST /videos/generations → GET /async-result/{id}，JWT 鉴权。
  - `sure-ai-doubao`：火山 Seedance 2.5/2.0，POST /contents/generations/tasks → GET 同路径/{id}。
  - `sure-ai-azure`：Azure Sora 2 预览，POST /openai/v1/video/generations/jobs?api-version=preview → GET 同路径/{id}，api-key 头鉴权。
- 6 个平台 TTS 接入：
  - `sure-ai-openai`：tts-1 / tts-1-hd / gpt-4o-mini-tts，POST /v1/audio/speech，二进制音频直返。
  - `sure-ai-qwen`：CosyVoice v3.5+ / qwen-audio-tts，POST SpeechSynthesizer，JSON 含 audio.url（24h 有效）。
  - `sure-ai-zhipu`：GLM-TTS，与 OpenAI 同协议，二进制音频直返。
  - `sure-ai-doubao`：豆包语音合成 seed-tts-2.0，X-Api-Key + X-Api-Resource-Id 头，JSON 含 base64 data 分片（SDK 内部解码）。
  - `sure-ai-baidu`：百度短文本语音合成，POST tsn.baidu.com/text2audio，form-urlencoded，access_token 作 tok 字段，二进制音频直返。
  - `sure-ai-azure`：Azure AI Speech，SSML XML + Ocp-Apim-Subscription-Key + X-Microsoft-OutputFormat 头，二进制音频直返。
- 6 个平台 STT 接入：
  - `sure-ai-openai`：whisper-1 / gpt-4o-transcribe，POST /v1/audio/transcriptions，multipart/form-data，JSON 含 text/segments/words。
  - `sure-ai-qwen`：Qwen-ASR qwen3-asr-flash，chat/completions 兼容模式，input_audio data URI，JSON 含 choices[0].message.content。
  - `sure-ai-zhipu`：GLM-ASR-2512，与 OpenAI 同协议 multipart。
  - `sure-ai-doubao`：豆包录音文件识别，异步 submit+query，X-Api-Key 头，audio.url 为 data URI，JSON 含 result.text。
  - `sure-ai-baidu`：百度短语音识别，POST vop.baidu.com/server_api，JSON 含 speech base64 + len，JSON 含 result[]。
  - `sure-ai-azure`：Azure AI Speech STT，二进制 body + Ocp-Apim-Subscription-Key 头，JSON 含 DisplayText。
- 各平台 `XxxUtil` 新增 `video(model, prompt)` / `video(VideoRequest)` / `tts(model, text, voice)` / `tts(TtsRequest)` / `stt(model, audioData)` / `stt(SttRequest)` 便捷入口与独立单例（异步/非兼容平台）。
- 工程集成：examples 新增 `VideoDemo`（多平台视频生成演示）、`AudioDemo`（TTS/STT 演示），`ExamplesRunner` 新增 `video` / `audio` case。
- 文档：新增 `docs/video.md`（架构图、快速上手、5 平台配置、异步轮询说明、平台对比表）、`docs/audio.md`（TTS/STT 架构、6 平台配置、响应形态说明、上传格式对比、平台对比表）。
- `sure-ai-core`：P1 能力抽象层——
  - 重排序：`RerankClient` 接口 + `RerankRequest`（model/query/documents/topN/extra）+ `RerankResponse`（model/results/rawJson）+ `RerankResult`（index/relevanceScore/document/rawJson）。
  - 批处理：`BatchClient` 接口（createBatch/getBatch）+ `BatchRequest`（model/inputFileId/requests/completionWindow/metadata/extra）+ `BatchResponse`（id/status/createdAt/completedAt/RequestCounts/error/rawJson）。
  - 结构化输出：`ChatRequest` 新增 `responseFormat(Object)` 字段（`"json_object"` 字符串或 JSON Schema 对象）。
  - 多模态：`MessagePart` sealed 内容块架构（permits `TextPart`/`ImagePart`/`DocumentPart`），`ImagePart.ofUrl/ofBase64`（`resolvedUrl()` 输出 data URI）、`DocumentPart.ofBase64/ofFileId`、`TextPart.ofWithCache` 扩展。
  - Prompt 缓存：`CacheControl` record（`ephemeral()`）挂到 `TextPart`。
  - 工具：`JsonMapper`（record 反射双向映射 `fromJson`/`toJson`/`toJsonObject`），零第三方依赖。
- `sure-ai-rag`：重排链路集成——`Reranker` 接口 + `ClientReranker`（适配 core `RerankClient`），`VectorRetriever.Builder.reranker(...)` 与 `RagPipeline.Builder.reranker(...)` 注入二阶段精排。
- P1 平台接入：
  - `sure-ai-qwen`：`QwenRerankClient`（POST `/reranks`，OpenAI 兼容），`QwenUtil.rerank(query, documents)` / `rerank(RerankRequest)` / `rerankClient()` / `resetRerankClient()`；模型常量 `QwenModels.QWEN3_RERANK`。
  - `sure-ai-openai` / `sure-ai-azure` / `sure-ai-zhipu`：Batches 接入（`OpenAiBatchClient` 带 `waitForCompletion(batchId, timeoutMs)` 2s 轮询；`AzureBatchClient` api-version 查询参数 + api-key 头；`ZhipuBatchClient` JWT 鉴权），各 `XxxUtil` 新增 `batchClient()`/`batch()`/`getBatch()`/`resetBatchClient()`。
  - `sure-ai-anthropic`：`AnthropicBatchClient`（POST `/v1/messages/batches`，requests 内联 + custom_id，GET 查询 `processing_status`），并以强制 tool_use 模拟 `responseFormat`。
  - `sure-ai-gemini` / `sure-ai-anthropic` / `sure-ai-baidu` 等：多模态（ImagePart/DocumentPart）、结构化输出（Gemini `responseMimeType`+`responseSchema`；Anthropic 注入 `structured_output` 工具；百度字符串取值）、PDF 输入、Prompt 缓存（Anthropic `cache_control`；Gemini `extra("cachedContent", name)`；OpenAI 自动缓存）全量适配。
- 工程集成：examples 新增 `RerankDemo`（通义千问重排）、`StructuredOutputDemo`（OpenAI json_object + JsonMapper）、`MultimodalDemo`（OpenAI 视觉模型图片理解）、`BatchDemo`（OpenAI Batches 提交/查询，缺 Key 或 input_file_id 优雅跳过），`ExamplesRunner` 新增 `rerank`/`structured`/`multimodal`/`batch` case 与帮助行。
- 文档：新增 `docs/rerank.md`（RerankClient 架构、Qwen 接入、RAG 集成、智谱未开放说明）、`docs/structured-output.md`（response_format 各平台差异、JsonMapper 用法、平台对比表）、`docs/multimodal.md`（内容块架构、图片/data URL 与裸 base64 对比、PDF 支持矩阵、Prompt 缓存）、`docs/batches.md`（BatchClient 架构、OpenAI 协议族与 Anthropic 协议族对比、异步轮询说明）。
- `sure-ai-core`：P2 能力抽象层——
  - Realtime 实时语音：`RealtimeClient` 接口（connect/sendAudio/sendText/close/isConnected）、`RealtimeEventListener`（onTranscript/onAudio/onError/onClose/onEvent）、`RealtimeConnector`（可注入 WebSocket 连接抽象）、`AbstractRealtimeClient`（通用 WebSocket 建连/帧分发，子类覆盖 `buildUri()`/`handleMessage()`）、`DefaultRealtimeConnector`（JDK `java.net.http.WebSocket` 默认实现）。
  - 思考模式：`ChatRequest.reasoningEffort(String)` + `ChatRequest.thinkingConfig(Object)`；`ChatMessage.reasoningContent(String)` 统一思维链出口。
  - Grounding 联网：`ChatRequest.grounding(Object)`；`ChatResponse.groundingSources(List<GroundingSource>)` + `GroundingSource` record（title/url/content）。
  - 微调：`FineTuneClient` 接口（createFineTune/getFineTune/uploadTrainingFile）+ `FineTuneRequest`（model/trainingFileId/hyperparameters/suffix）+ `FineTuneResponse`（id/status/model/fineTunedModel/createdAt/completedAt/error/rawJson，`isCompleted()`/`isFailed()`）。
  - 内容审核：`ModerationClient` 接口（moderate）+ `ModerationRequest`（model/input，默认 `text-moderation-latest`）+ `ModerationResponse`（id/model/results，`flagged()`）+ `ModerationResult`（flagged/categoryScores/categories，5 类别常量 sexual/hate/harassment/self-harm/violence）。
  - 模型列表：`ModelsClient` 接口（listModels）+ `Model` record（id/created/ownedBy/object/rawJson）。
- P2 平台接入：
  - `sure-ai-openai`：`OpenAiRealtimeClient`（`wss://api.openai.com/v1/realtime?model=`，Bearer，`response.output_audio.delta` 路由）；思考模式 `reasoning_effort` 序列化 + `reasoning_content` 解析；Grounding `web_search` 工具注入 + annotations 解析；微调 `/v1/fine_tuning/jobs` + `/v1/files`（purpose=fine-tune）；审核 `/v1/moderations`；模型列表 `/v1/models`（后五项复用 `OpenAiCompatClient` 内置实现）。
  - `sure-ai-gemini`：`GeminiRealtimeClient`（`?key=` 鉴权，首连发 setup，`serverContent` 路由）；思考模式 `thinkingConfig → generationConfig.thinkingConfig` + `parts[].thought` 解析；Grounding `googleSearch` 工具 + `groundingMetadata` 解析；模型列表 `GET /v1beta/models`（`models[].name` 去前缀）。
  - `sure-ai-qwen`：`QwenRealtimeClient`（DashScope `wss .../api-ws/v1/inference`，Bearer，`output.*` 路由）；思考模式覆盖序列化（`enable_thinking` + `thinking_budget`，移除 `reasoning_effort`）；Grounding 覆盖序列化（`enable_search:true` 布尔，移除 web_search 工具）；模型列表走 OpenAI 兼容模式（继承 core）。
  - `sure-ai-zhipu`：`ZhipuRealtimeClient`（JWT 鉴权签发 HS256）；Grounding 继承 core（web_search 工具）；模型列表无公开 REST API，`listModels()` 抛 `AiException`。
  - `sure-ai-doubao`：`DoubaoRealtimeClient`（`X-Api-Key` + `X-Api-Resource-Id` 握手头，可 `extraHeaders` 覆盖）；Grounding 继承 core（web_search 工具）；模型列表无公开 REST API，`listModels()` 抛 `AiException`。
  - `sure-ai-anthropic`：思考模式 `thinkingConfig → thinking` 字段 + `content[] type:thinking` block 解析；模型列表 `GET /v1/models`（`data[].id`）。
  - `sure-ai-azure`：模型列表 `/openai/models?api-version=`（api-key 头）；审核 `/openai/moderations?api-version=`；微调 `/openai/fine_tuning/jobs?api-version=`；思考模式复用 core（`reasoning_effort`）。
  - `sure-ai-baidu`：微调千帆 SFT 端点，access_token 鉴权，任务状态码映射。
  - Qwen/Zhipu/Doubao 微调走控制台/Notebook，未单独适配（JavaDoc 注明）。
  - 各平台 `XxxUtil` 新增 `realtimeClient(model, listener)` 单例与 `resetRealtimeClient()`。
- 工程集成：examples 新增 `RealtimeDemo`（OpenAI Realtime API 用法演示，不实际建连）、`ThinkingDemo`（reasoningEffort=high + reasoningContent）、`GroundingDemo`（web_search + 来源打印）、`FineTuneDemo`（FineTuneRequest + createFineTune/getFineTune，缺 training_file_id 仅打印用法）、`ModerationDemo`（moderate + flagged/类别分数）、`ModelsDemo`（listModels 打印），`ExamplesRunner` 新增 `realtime`/`thinking`/`grounding`/`finetune`/`moderation`/`models` case 与帮助行；单平台异常 try/catch 不中断、缺 Key 优雅跳过。
- 文档：新增 `docs/realtime.md`（连接器抽象架构图、5 平台 WSS 端点与鉴权对比、事件类型、FakeConnector 测试策略）、`docs/thinking.md`（各平台字段差异、reasoningContent 解析、对比表）、`docs/grounding.md`（工具式 vs 布尔式双范式、来源解析、对比表）、`docs/fine-tuning.md`（FineTuneClient 架构、文件上传、异步轮询、各平台端点与状态对比）、`docs/moderation.md`（ModerationClient 架构、类别常量、OpenAI/Azure 配置）、`docs/models.md`（ModelsClient 架构、各平台端点、不支持平台说明）。

## [0.1.0] - 2026-09-17

### Added
- 初始版本：sureai 多模块 Maven 工程，父 POM 集成 jacoco/checkstyle/spotbugs/license 质量门禁。
- `sure-ai-core`：自研零依赖 JSON 组件、SSE 解析器、OpenAI 兼容协议引擎、通用模型类与异常体系。
- 11 个平台独立模块，每个模块提供 Client / Util 静态入口 / Models 常量 / package-info：
  - `sure-ai-openai`：OpenAI Chat Completions / Embeddings，Bearer + Organization 头。
  - `sure-ai-azure`：Azure OpenAI，api-key 头 + api-version 查询参数。
  - `sure-ai-anthropic`：Claude Messages API，x-api-key + anthropic-version，SSE 事件流。
  - `sure-ai-gemini`：Google Generative Language，key 查询参数，generateContent / streamGenerateContent / embedContent。
  - `sure-ai-deepseek`：DeepSeek 兼容模式，deepseek-chat / deepseek-reasoner。
  - `sure-ai-qwen`：阿里云 DashScope 兼容模式，qwen 系列 + text-embedding。
  - `sure-ai-zhipu`：智谱 GLM，apiKey(id.secret) 签发 HS256 JWT 鉴权。
  - `sure-ai-moonshot`：月之暗面 Kimi，兼容协议。
  - `sure-ai-doubao`：火山引擎方舟 Ark，endpoint id 或模型 ID。
  - `sure-ai-baidu`：百度千帆/文心，OAuth access_token 缓存 + 流式 SSE。
  - `sure-ai-ollama`：本地 Ollama，原生 /api/chat ndjson 流式 + /api/embed。
- `sure-ai-bom`：统一版本管理 BOM。
- `sure-ai-all`：聚合全部平台模块。
- `sure-ai-examples`：各平台使用示例，缺 key 优雅跳过。
- 全套开源治理文件：LICENSE / NOTICE / CONTRIBUTING / CODE_OF_CONDUCT / SECURITY / ROADMAP。
- GitHub Actions CI（JDK21/25 矩阵）、dependabot、issue/PR 模板。
- 中英双 README 与 docs/ 平台文档。

### Notes
- 运行期唯一第三方依赖为 `io.github.tasure:sure-core:0.2.0`；HTTP 使用 JDK21 `java.net.http.HttpClient`，JSON 为自研零依赖组件。
- 平台模块之间零相互依赖，引入单个平台不会传递拉入其他平台。
