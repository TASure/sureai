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
- [ ] **重试可观测性**：重试事件回调（RetryListener），指标埋点（Micrometer 可选适配，默认无依赖）。
- [ ] **图像生成**：DALL·E / 通义万相 / 文心一格等图像生成接口抽象（`ImageClient`）。
- [ ] **音频**：Whisper / TTS 接口抽象（`AudioClient`）。
- [ ] **Function Calling 增强**：自动参数校验（JSON Schema → Java Bean 校验）、工具注册中心。
- [ ] **结构化输出**：JSON mode / response_format 统一抽象，支持强类型反序列化。

## P2 — 规划中（0.3.0+）

- [ ] **RAG 增强**：文档加载器（URL / 文件 / 爬虫）、重排序（Rerank）、混合检索（BM25 + 向量）、外部向量库适配（Milvus / pgvector / Chroma）。
- [ ] **Prompt 模板**：变量替换、few-shot 管理、模板热加载。
- [ ] **Agent 编排**：ReAct / Plan-and-Execute 轻量编排器，基于 sureai 原语。
- [ ] **更多平台**：xAI (Grok)、Mistral、Cohere、Bedrock（AWS SigV4）、本地 llama.cpp server。
- [ ] **响应缓存**：基于 prompt hash 的本地 / Redis 缓存层。
- [ ] **限流熔断**：平台级 QPS 限流与熔断（基于 sure-core RateLimiter 扩展）。
- [ ] **Benchmark**：JMH 性能基准（序列化 / 反序列化 / 客户端吞吐）。

## 非目标

- 不做 LLM 应用框架（不替代 langchain4j / Spring AI 的高级编排能力），专注"接入层"。
- 不引入重量级第三方依赖，保持 sure-core 单依赖的轻量定位。
- 不做 GUI / CLI 工具。
