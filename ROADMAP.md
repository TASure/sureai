# Roadmap

sureai 的演进路线图。欢迎通过 Issue 提交建议。

## P0 — 已完成（0.1.0）

- [x] 多模块 Maven 工程骨架与质量门禁（jacoco / checkstyle / spotbugs / license）
- [x] sure-ai-core：自研 JSON、SSE 解析、OpenAI 兼容协议引擎、通用模型类
- [x] 11 个主流平台独立模块（OpenAI / Azure / Anthropic / Gemini / DeepSeek / Qwen / Zhipu / Moonshot / Doubao / Baidu / Ollama）
- [x] 静态工具类入口（`<Pla>Util`），环境变量自动读取
- [x] 本地 HttpServer mock 测试，零真实网络调用
- [x] BOM / all 聚合模块 / examples
- [x] 开源治理文件与 CI

## P1 — 进行中（0.2.0）

- [x] **RAG 组件**：`sure-ai-rag` 模块——文本分块（递归字符分块）、向量存储抽象（内置进程内实现）、向量化适配、向量检索器、端到端 `RagPipeline`（索引 → 检索 → 增强 → 生成）。见 [docs/rag.md](docs/rag.md)。
- [ ] **Spring Boot Starter**：`sure-ai-spring-boot-starter`，自动配置 + `@Autowired` 注入，配置属性 `sure.ai.<platform>.api-key` 等。
- [x] **重试可观测性**：重试事件回调（RetryListener），指标埋点（内置零依赖 AiMetrics + Micrometer 可选适配），见 [docs/observability.md](docs/observability.md)。
- [x] **图像生成**：`ImageClient` 抽象 + 6 平台接入（OpenAI DALL·E / Azure / 通义万相 / 智谱 CogView / 文心一格 / Gemini），异步平台内部轮询屏蔽，对外同步返回。见 [docs/images.md](docs/images.md)。
- [x] **视频生成**：`VideoClient` 抽象 + 5 平台接入（OpenAI Sora / 通义万相 Wan / 智谱 CogVideoX / 火山 Seedance / Azure Sora 2），全平台异步任务轮询屏蔽，对外同步返回。见 [docs/video.md](docs/video.md)。
- [x] **音频（TTS/STT）**：`AudioClient` 抽象（TTS synthesize + STT transcribe）+ 6 平台接入（OpenAI / 通义 CosyVoice / 智谱 GLM-TTS/ASR / 火山豆包 / 百度 / Azure Speech），支持二进制音频/URL/Base64 三种响应形态与 multipart/base64/二进制四种上传格式。见 [docs/audio.md](docs/audio.md)。
- [x] **Rerank 重排序**：`RerankClient` 抽象 + 通义千问 qwen3-rerank 接入 + RAG 检索链路二阶段精排集成（`Reranker`/`ClientReranker`/`VectorRetriever.reranker`）。见 [docs/rerank.md](docs/rerank.md)。
- [x] **结构化输出**：`response_format` 统一抽象 + `JsonMapper` 强类型 record 反序列化 + 8 平台适配（Anthropic 自动 tool_use 模拟、Gemini responseSchema、百度字符串取值）。见 [docs/structured-output.md](docs/structured-output.md)。
- [x] **多模态图像理解**：`MessagePart` 内容块架构（TextPart/ImagePart）+ 8 平台图片输入适配（data URL vs 裸 base64 自动转换）。见 [docs/multimodal.md](docs/multimodal.md)。
- [x] **PDF 文档输入**：`DocumentPart` 内容块 + 5 平台适配（OpenAI/Azure/Gemini/Anthropic/Qwen；百度不支持抛 `AiException`）。
- [x] **Prompt 缓存**：Anthropic `cache_control: ephemeral` + Gemini `cachedContent`（extra 传入）+ OpenAI 自动缓存（无需参数）。
- [x] **Batches 批处理**：`BatchClient` 抽象 + OpenAI/Azure/智谱（OpenAI 协议族）+ Anthropic（内联 requests 不同协议）接入，内置 2s 轮询。见 [docs/batches.md](docs/batches.md)。
- [x] **Function Calling 增强**：自动参数校验（JSON Schema → Java Bean 校验）、工具注册中心（`sure-ai-agent`，见 [docs/agent.md](docs/agent.md)）。

## P2 — 实时语音与平台服务（0.2.0）

- [x] **Realtime 实时语音对话**：`RealtimeClient` 抽象 + 5 平台接入（OpenAI / Gemini / 通义千问 / 智谱 / 豆包），`RealtimeConnector` 连接器可注入便于 mock。见 [docs/realtime.md](docs/realtime.md)。
- [x] **思考模式**：`reasoning_effort` / `thinkingConfig` 统一抽象 + 5 平台适配（OpenAI / Azure / Gemini / Anthropic / 通义千问），响应 `reasoningContent` 思维链解析。见 [docs/thinking.md](docs/thinking.md)。
- [x] **Grounding 联网搜索**：`grounding` 统一抽象 + 工具式/布尔式双范式 + 6 平台适配（OpenAI / Azure / Gemini / 通义千问 / 智谱 / 豆包），响应引用来源解析。见 [docs/grounding.md](docs/grounding.md)。
- [x] **微调**：`FineTuneClient` 抽象 + 3 平台接入（OpenAI / Azure / 百度千帆），训练文件上传 + 任务创建/异步轮询。见 [docs/fine-tuning.md](docs/fine-tuning.md)。
- [x] **内容审核**：`ModerationClient` 抽象 + OpenAI / Azure 接入，类别与分数归一。见 [docs/moderation.md](docs/moderation.md)。
- [x] **模型列表管理**：`ModelsClient` 抽象 + 5 平台接入（OpenAI / Azure / Gemini / Anthropic / 通义千问）；智谱 / 豆包无公开 REST API，抛 `AiException`。见 [docs/models.md](docs/models.md)。

## P3 — 规划中（0.3.0+）

- [x] **RAG 增强——文档加载器**：`DocumentLoader` + `TxtDocumentLoader`（本地文件/输入流/字符串）+ `UrlDocumentLoader`（JDK HttpClient + 基础 HTML 去标签）。见 [docs/rag.md](docs/rag.md#文档加载器)。
- [x] **RAG 增强——混合检索**：`KeywordRetriever`（BM25 纯 JDK 实现）+ `HybridRetriever`（向量 + 关键词加权融合，可配权重）。见 [docs/rag.md](docs/rag.md#混合检索)。
- [x] **RAG 增强——分块策略扩展**：`MarkdownTextSplitter`（按标题层级分块、保留标题上下文）+ `FixedSizeTextSplitter`（固定字符大小 + 重叠）。
- [ ] **RAG 增强——外部向量库适配**：Milvus / pgvector / Chroma（重排序 Rerank 已在 P1 完成）。
- [ ] **Prompt 模板**：变量替换、few-shot 管理、模板热加载。
- [x] **Agent 编排——ReAct 多工具循环**：`sure-ai-agent`（`ToolRegistry`/`ReActAgent`/`ToolArgumentValidator`/`AgentListener`/`AgentUtil`），基于 sureai 原语，见 [docs/agent.md](docs/agent.md)。
- [ ] **Agent 编排——Plan-and-Execute**：`PlanExecuteAgent` 当前为骨架（run 抛 UnsupportedOperationException），待后续迭代实现「JSON 步骤列表 → 逐步执行 → 汇总」。
- [ ] **更多平台**：xAI (Grok)、Mistral、Cohere、Bedrock（AWS SigV4）、本地 llama.cpp server。
- [ ] **响应缓存**：基于 prompt hash 的本地 / Redis 缓存层。
- [x] **限流**：客户端 QPS 限流已完成（`AiConfig.rateLimitQps`，基于 sure-core `RateLimiter` 令牌桶，见 [docs/observability.md](docs/observability.md)）；熔断（circuit breaker）后续规划。
- [ ] **Benchmark**：JMH 性能基准（序列化 / 反序列化 / 客户端吞吐）。

## 非目标

- 不做 LLM 应用框架（不替代 langchain4j / Spring AI 的高级编排能力），专注"接入层"。
- 不引入重量级第三方依赖，保持 sure-core 单依赖的轻量定位。
- 不做 GUI / CLI 工具。
