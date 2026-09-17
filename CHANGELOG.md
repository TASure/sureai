# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `sure-ai-rag`：RAG（检索增强生成）能力模块，仅依赖 sure-ai-core，与具体平台解耦：
  - 文本分块：`TextSplitter` 接口 + 递归字符分块器（块大小/重叠/分隔符优先级，参考 LangChain 思路的纯 JDK 实现）。
  - 向量存储：`VectorStore` 抽象 + 进程内实现 `InMemoryVectorStore`（余弦相似度、topK、相似度阈值），可实现接口接入 Milvus/FAISS/pgvector 等外部向量库。
  - 向量化：`EmbeddingProvider` 抽象 + `ClientEmbeddingProvider` 适配 sure-ai-core 的 `EmbeddingClient`，任意平台客户端可直接组合。
  - 检索：`Retriever` 抽象 + `VectorRetriever`（查询向量化 → 相似度检索 → 文档化，支持最低相似度过滤）。
  - 管线：`RagPipeline` 端到端编排（索引 → 检索 → 增强 → 生成），支持自定义分块器/向量库/检索器/系统提示词模板/默认 topK。
  - 静态入口 `RagUtil`：splitter() / inMemoryStore() / pipeline(...) 一行创建。
- 工程集成：父 POM 与 `sure-ai-bom` 收录 `sure-ai-rag`，`sure-ai-all` 聚合引入，examples 新增 `RagDemo`（基于 OpenAI，缺 Key 自动跳过）。
- 文档：新增 `docs/rag.md`（架构图、快速上手、自定义配置、外部向量库接入、平台支持与对比）。

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
