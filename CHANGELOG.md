# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.0] - Unreleased

本版本线为「生产级信任」专项：在不改变任何运行期行为（零运行期依赖变化）的前提下，补齐发布供应链信任链、依赖漏洞扫描、解析器模糊健壮性测试与社区工程化基建。

### Added
- **CycloneDX SBOM**：`cyclonedx-maven-plugin`（仅 build 期插件）在聚合模块 `sure-ai-all` 的 `package` 阶段生成完整 CycloneDX BOM（31 个组件：30 个内部 `io.github.tasure:*` + 运行期唯一第三方依赖 `sure-core`），并随 `mvn deploy` 附加为 Maven 构件；CI Release 把 `bom.json` / `bom.xml` 归档到 GitHub Release。见 `docs/RELEASING.md#sbomcyclonedx`。
- **SLSA Build Provenance 出处签名**：`actions/attest-build-provenance@v2`（Sigstore keyless）对 SBOM + 全部模块 jar 做 DSSE 签名，上传仓库 attestations API，对应 SLSA Build L3；消费方用 `gh attestation verify <artifact> --repo TASure/sureai` 在线校验。见 `docs/RELEASING.md#slsa--软件出处build-provenance-attestation`。
- **安全政策与依赖漏洞扫描**：重写 `SECURITY.md`（受支持版本线、90 天协调披露、响应时限承诺）；新增独立 `security` Maven profile 挂 `org.owasp:dependency-check-maven`，命令式 `-Psecurity` 在聚合点扫描 NVD CVE，门禁 `failBuildOnCVSS=7`；`ci.yml` 加并行 `dependency-check` job（`continue-on-error: true`）。当前基线 **0 CVE**。
- **解析器模糊健壮性测试（Fuzz）**：6 个确定性 FuzzTest（固定种子 `Random(42)`、零新依赖、零网络）覆盖 `JsonParser` / `SseLineReader` / MCP 帧 / CLI 参数 / Redis RESP / FilterExpression。
- **社区工程化基建**：新增 `.github/CODEOWNERS`（默认 owner `@TASure`）；增强 `ISSUE_TEMPLATE`（bug 补环境/安全提示，feature 补影响面）与 `PULL_REQUEST_TEMPLATE`（补 checkstyle / 文档 / CHANGELOG 自检项）；`CONTRIBUTING.md` 追加新贡献者流程、`good first issue` 指引、Discussions 引导；README 顶部换为 live 徽章（Maven Central / CI / License / 平台数）并加「讨论与支持」段（中英文同步）。
- **信任手册 `docs/trust.md`**：GPG 签名 + SBOM + SLSA 三层信任链 + 依赖扫描 + Fuzz 的一页式操作速查（是什么/怎么生成/怎么验证/CI 在哪跑 + 快速命令表）。

### Fixed
- **Fuzz 发现并修复 4 个解析器健壮性缺陷**：
  - `JsonParser` 超深嵌套触发 `StackOverflowError`——加 `MAX_DEPTH=1000` 上限；
  - `JsonParser` 畸形数字使 `NumberFormatException` 逃逸——统一收敛为业务异常 `AiException`；
  - `RedisVectorStore` RESP 长度字段畸形数字 `NumberFormatException` 逃逸——收敛为 `AiException`；
  - `RedisVectorStore` RESP 超大长度导致 OOM / 负下标——加长度上限保护。

### 测试
- 新增 21 个测试（全部为 Fuzz 模糊用例）；全工程合计 **1283** 个测试，`mvn -B verify -Dgpg.skip=true` BUILD SUCCESS（checkstyle / spotbugs / jacoco / license 零违规）。聚合 SBOM 31 个组件，OWASP dependency-check 扫描 **0 CVE**。

## [2.0.0] - Unreleased

### Added
- **命令行工具（sure-ai-cli `com.sure.ai.cli`）**：应用层零依赖终端，不写一行 Java 即可问答。
  - 五子命令：`chat`（一次性同步问答）/ `stream`（SSE 逐片打印）/ `rag`（本地 UTF-8 纯文本文档 RAG：递归字符分块 + `InMemoryVectorStore` topK=4，全程不下载文档）/ `list`（列全部平台与默认模型）/ `repl`（交互多轮，`exit`/`quit` 退出）。
  - 全局选项 `--provider` / `--api-key` / `--model` / `--base-url`，一键切换全部 23 个平台；凭证优先级 `--api-key` 命令行 > 平台对应 `SURE_AI_*` 环境变量；退出码 0 成功 / 1 用户输入错误 / 2 调用平台异常。
  - 零第三方 CLI 依赖：手写 `ArgsParser`（不引 picocli/jline），`CliRunner` 纯逻辑（输出写注入 `PrintStream`），`ClientFactory` 装配接缝便于测试注入 `FakeAiClient`（同时实现 `AiClient`+`EmbeddingClient`，零真实网络）。
  - 构建：shade 打 fat jar（`ManifestResourceTransformer` 主类 `com.sure.ai.cli.Main` + `ServicesResourceTransformer` 合并 SPI，仅剔除签名文件 `*.SF/*.DSA/*.RSA`、保留各平台 jar 的 `META-INF/native-image/**`）；支持 GraalVM native-image 编译为单文件可执行。注册在父工程 `<modules>`，但不进入 `sure-ai-all`/`sure-ai-bom` 聚合链。见 `docs/cli.md`。
- **Maven 工程脚手架（sure-ai-archetype）**：`mvn archetype:generate` 一键生成 Hello World 工程。
  - 产物：pom（`dependencyManagement` import `sure-ai-bom`）+ 一个可运行的单平台 Client 主类（默认 OpenAI）+ README + `.gitignore`；platform 属性可切换 23 个平台。
  - 应用层脚手架，不进入 `sure-ai-all`/`sure-ai-bom` 聚合链；用法见 `docs/COOKBOOK.md` 场景 8。
- **GraalVM native-image AOT 全库加固（零新依赖、不改主代码）**：补齐 native-image 构建器自动发现的元数据 + 元数据完整性守卫测试。
  - `META-INF/native-image/io.github.tasure/<artifactId>/reflect-config.json` 共 42 条：sure-ai-core 30 条（`com.sure.ai.model` 全部 record，含嵌套 record）+ sure-ai-agent 8 条（`agent.event` 事件 record）+ sure-ai-mcp-server 4 条（`tool` 包请求 record，含嵌套 `ChatToolRequest$Message`）。
  - core 提供 `resource-config.json`（空配置）+ `native-image.properties`（全局编译参数 `-H:+AddAllCharsets --enable-url-protocols=https,http`）；sure-ai-cli 自带 `native-image.properties` 继承同参数、`reflect-config.json` 为空 `[]`。
  - 反射热点：`JsonMapper`（record ↔ JSON，`getRecordComponents` + 构造器/访问器反射）与 `JsonSchemaGenerator`（record → JSON Schema）；core 使用 JDK 自带 `java.net.http.HttpClient`（HTTP/1.1 + SSE），故需全字符集与 https/http 协议提供者。
  - `com.sure.ai.util.NativeImageMetadataTest`（纯 JVM、零网络）守卫：reflect-config 存在可解析、classpath 扫描 `com.sure.ai.model` 包全部 record（含嵌套）均已注册、每条目四开关（allDeclaredConstructors/Methods/Fields/RecordComponents）齐全、native-image.properties 含两参数、resource-config 可解析。见 `docs/native-image.md`。
- **Cookbook 场景菜谱（`docs/COOKBOOK.md`）+ 3 个完整示例应用（sure-ai-examples）**：
  - `docs/COOKBOOK.md`：9 个「照着抄」最小可运行场景——5 分钟入门、单平台 chat（含流式）、多平台统一网关（路由策略 + 故障转移）、RAG 知识库问答、Agent 工具调用（Function Calling）、异步/虚拟线程、可观测性（OTel 桥接 + Metrics）、CLI 使用、archetype 生成工程、GraalVM native 编译；所有代码块与当前源码 API 逐字对齐。
  - `sure-ai-examples`：`com.sure.ai.examples.ExamplesRunner` 一键运行 3 个离线示例——`knowledgebase`（知识库 RAG）/ `gateway-multi`（三平台同问对比）/ `code-assistant`（流式 + 工具调用）；未配 Key 自动走 Fake 分支，不发起真实网络。
  - 文档收尾：README 中英文 Quick Start 改为 5 分钟入门（BOM 依赖 + 最小 chat + 跑通），特性区与文档索引补 CLI / archetype / GraalVM native / Cookbook 入口。

### 测试
- 新增 27 个测试（GraalVM 元数据守卫 5 + CLI 17 + archetype 5 + Cookbook/示例应用 0）；全工程合计 1262 测试，`mvn -B verify -Dgpg.skip=true` BUILD SUCCESS（checkstyle / spotbugs / jacoco / license 零违规）。3 个示例应用以离线 smoke 验证（`ExamplesRunner` Fake 分支，沿用 examples 模块零测试先例）；CI 沙箱无 GraalVM，native 端到端建议在有 GraalVM 的环境自行冒烟。

## [1.9.0] - Unreleased

### Added
- **7 个新平台模块（均为 OpenAI 兼容协议，`com.sure.ai.<slug>`）**：统一继承 `OpenAiCompatClient`、Bearer 鉴权、静态 `XxxUtil` 入口（`chat` / `chat(ChatRequest)` / `chatStream` / `client` / `init`），env 前缀 `SURE_AI_<PLATFORM>_API_KEY` / `_BASE_URL`。
  - `sure-ai-minimax`（MiniMax 稀宇科技）：默认 `https://api.minimax.cn/v1`（国际站 `api.minimax.io/v1`）；`MiniMaxModels` 常量 `MiniMax-M3`（1M 上下文旗舰）/ `MiniMax-M2.7` / `MiniMax-M2.5`；`MiniMax-M3` 的 `thinking.type` / `reasoning_split` 等经 `ChatRequest.Builder#extra` 透传。仅 `CHAT`/`CHAT_STREAM`。
  - `sure-ai-stepfun`（阶跃星辰）：默认 `https://api.stepfun.com/v1`（国际站 `api.stepfun.ai/v1`）；`StepFunModels` 常量 `step-5-preview`（旗舰 MoE）/ `step-2-mini` / `step-2-16k`。仅 `CHAT`/`CHAT_STREAM`。
  - `sure-ai-baichuan`（百川智能）：默认 `https://api.baichuan-ai.com/v1`；`BaichuanModels` 常量 `Baichuan4-Turbo` / `Baichuan4` / `Baichuan3-Turbo`。仅 `CHAT`/`CHAT_STREAM`（其向量为原生 `/v1/embedding` 单数路径，与 OpenAI 不兼容，未声明 EMBED）。
  - `sure-ai-lingyi`（01.AI 零一万物）：默认 `https://api.lingyiwanwu.com/v1`；`LingyiModels` 常量 `yi-large` / `yi-medium` / `yi-lightning`。仅 `CHAT`/`CHAT_STREAM`。
  - `sure-ai-siliconflow`（硅基流动）：默认 `https://api.siliconflow.cn/v1`（国际站 `api.siliconflow.com/v1`）；模型形如 `组织/模型名`，`SiliconFlowModels` 常量 `deepseek-ai/DeepSeek-V3` / `Qwen/Qwen2.5-72B-Instruct` / `Qwen/Qwen3-32B` / `BAAI/bge-m3`；`SiliconFlowUtil.embed(model, List<String>)` 接入向量。声明 `CHAT`/`CHAT_STREAM`/`EMBED`。
  - `sure-ai-hunyuan`（腾讯混元）：默认 `https://api.hunyuan.cloud.tencent.com/v1`（提供 `HunyuanClient.TOKENHUB_BASE_URL` 迁移端点）；`HunyuanModels` 常量 `hunyuan-turbos-latest` / `hunyuan-t1-latest` / `hunyuan-embedding`（1024 维）；`HunyuanUtil.embed(List<String>)` 固定向量模型。声明 `CHAT`/`CHAT_STREAM`/`EMBED`。
  - `sure-ai-spark`（讯飞星火）：默认 `https://spark-api-open.xf-yun.com/v1`；`SparkModels` 短名 `lite`（永久免费）/ `pro` / `max` / `general`；API Key 形如 `APIPath:APIKey` 整体作为 Bearer。仅 `CHAT`/`CHAT_STREAM`。
- **OpenTelemetry GenAI 语义约定桥接（sure-ai-otel `com.sure.ai.otel`）**：把 sureai 横切回调桥接为 OTel GenAI 指标，仅依赖 OTel API（provided 不传递），SDK/导出器由使用方自备。
  - `OtelSupport` 静态入口：`metricsCollector(MeterProvider[, providerName, operationName])` / `retryListener(...)` / `agentEventSink()`；分别返回 `MetricsCollector` / `RetryListener` / `AgentEventSink`。
  - `OtelGenAiMetrics`：映射 `gen_ai.client.operation.duration`（DoubleHistogram，s）/ `gen_ai.client.inference.usage.input_tokens` / `output_tokens`（LongCounter，{token}）；公共属性 `gen_ai.operation.name`（默认 chat）/ `gen_ai.provider.name` / `gen_ai.request.model` / `gen_ai.token.modality=text`，失败附低基数 `error.type`；Instrumentation Scope `com.sure.ai.otel`。
  - `OtelRetryListener` / `OtelAgentEventSink`：重试事件、AgentEvent→span 事件桥接。
  - **无感降级**：传 null / `MeterProvider.noop()` 即全空操作、零开销；runtime 未引入 OTel SDK 时本模块不被加载。
- **全链路异步 / 虚拟线程（sure-ai-core `com.sure.ai.client.async`）**：把阻塞式 AI IO 承载在 JDK 21 虚拟线程上，对同步 API 完全向后兼容。
  - `AiClient` default 方法族：`chatAsync(ChatRequest)` → `CompletableFuture<ChatResponse>`、`chatStreamAsync(ChatRequest, Consumer)` → `CompletableFuture<Void>`（走共享虚拟线程执行器）。
  - `AsyncClients` 一行包装工厂：`chat/embed/image/video/audio(client[, Executor])` 分别产出 `AsyncAiClient` / `AsyncEmbeddingClient`（`embedAsync`）/ `AsyncImageClient`（`generateAsync`）/ `AsyncVideoClient`（`generateAsync`）/ `AsyncAudioClient`（`synthesizeAsync` / `transcribeAsync`）；无参重载默认虚拟线程，带 Executor 重载支持受控线程池。
  - `AsyncExecutors`：`virtualThreadExecutor()` 进程级共享 per-task 虚拟线程执行器（命名前缀 `sureai-vt-`、守护线程、随进程存活不 shutdown）；`supplyAsync` / `runAsync` 注册「取消即中断工作线程」钩子，异常原样汇入 future。
- **Quarkus 扩展（sure-ai-quarkus-extension + sure-ai-quarkus-extension-deployment 双模块）**：按 `sure.ai.<platform>.api-key` 条件把 `XxxClient` 注册为 Arc 合成 `@Singleton` Bean，注入即用。
  - 配置根 `SureAiBuildConfig`（`@ConfigMapping(prefix="sure.ai")`，BUILD_TIME）+ 复用 `PlatformConfig`（api-key / secret-key / base-url / model / timeout / connect-timeout / proxy / organization / max-retries / rate-limit-qps / cache-ttl / extra-headers）；覆盖全部 22 个 `AiConfig` 族平台 + `bedrock` 独立 `BedrockGroup`（`access-key` 条件，SigV4 四元组）。
  - 双模块职责：runtime 侧 `SureAiClientFactory`（纯逻辑、不引入 Quarkus 类型，`PLATFORM_CLIENT_CLASSES` 映射 + `toAiConfig` + 反射 `newClient`）+ `@Recorder SureAiRecorder`；deployment 侧 `SureAiProcessor`（`@BuildStep` + `@Record(STATIC_INIT)`，`SyntheticBeanBuildItem` 按类型注册），扩展特性名 `sure-ai`。
  - 与 Spring Boot Starter 配置同构；core/平台模块零 Quarkus 依赖。
- 新增 docs：`docs/async.md`（异步/虚拟线程）、`docs/quarkus-extension.md`（Quarkus 扩展）、`docs/platforms/{minimax,stepfun,baichuan,lingyi,siliconflow,hunyuan,spark}.md`（7 平台专项）；`docs/observability.md` 增补 OpenTelemetry GenAI 桥接章节、`docs/capabilities.md` 增补 7 平台与 5 平台精确化后的能力清单；README 中英文特性区与文档索引加入口。

### Changed
- **能力声明精确化（capabilities guard 收口）**：OpenAI / Azure / 豆包 / 通义千问 / 智谱从「继承兼容基类全量兜底」改为在各 Client 显式覆写 `capabilities()`，只声明真实可用能力；Doubao / Qwen / Zhipu 的兼容面无 `/moderations`，移除 MODERATION 声明并同步测试，未支持能力由 `guard()` 发请求前快速失败。
- **Gemini Live（Realtime）WebSocket 端点由 v1alpha 精确化为 v1beta**：`GeminiRealtimeClient.WS_PATH` 现使用 `/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`（早期预览 v1alpha）；同步新增 Azure Realtime（`AzureRealtimeClient`，GA `/openai/v1/realtime`，api-key 握手）。

### 测试
- 新增 121 个测试（批次 1a 36 + 批次 1b 37 + OTel 14 + 异步 19 + Quarkus 15）；全工程合计 1235 测试，`mvn -B verify -Dgpg.skip=true` BUILD SUCCESS（checkstyle / spotbugs / jacoco / license 零违规）。

## [1.8.0] - Unreleased

### Added
- **高级检索策略（sure-ai-rag `com.sure.ai.rag.strategy`）**：在基础向量/BM25 检索之上提供一组可组合的 `Retriever` 增强器，全部实现 `Retriever` 接口、可互相包装，依赖 LLM 的策略在异常时降级而非抛错。
  - `HydeRetriever`（HyDE，假设文档嵌入）：LLM 先对查询生成陈述式「假设答案」，再用假设文档 embedding 检索；默认提示词 `请直接回答以下问题，无需解释…{query}`、maxTokens 256；LLM 异常/空文本时回退原 query 直接向量化。
  - `MultiQueryRetriever`：`QueryRewriter`（内置 `ModelQueryRewriter`）把 query 拆为 N 个子查询（默认 3），分别检索后用 RRF 融合去重（`score=Σ1/(k+rank)`，k 默认 60）；改写器异常退化为单路原 query。
  - `CorrectiveRetriever`（CRAG / Self-RAG 风格）：LLM 逐篇相关性自检（容错解析「相关/不相关」），有相关则过滤保留，全部不相关时——注入 `WebSearchProvider` 走网络搜索兜底，未注入则放宽召回（候选量 ×3，常量 `DEGRADED_RECALL_MULTIPLIER=3`）重检并在 metadata 打 `crag_degraded=true`；评估异常保守保留候选、不误删。
  - `ParentChildRetriever` + `splitter/ParentChildSplitter`：子块向量召回、按 `parentId` 聚合返回父块全文；父块 id 形如 `sourceId#p0`、子块 `sourceId#p0#c0`，子块 metadata 带 `parentId`；父块间按最高子块相似度排序。
  - `splitter/SemanticTextSplitter`：按相邻句 embedding 余弦相似度断点切分，默认自适应阈值=均值−标准差（可显式 `threshold(double)` 固定）；embedding 异常降级为 `FixedSizeTextSplitter(1000,100)`。
  - 多模态：`MultimodalDocument`（`id/parts/metadata`，聚合 `TextPart` 文本、`images()`）、`MultimodalIngestor`（文本向量化入库；注入 `ImageEmbedder` 时图片逐张向量化，否则仅登记 `mm_image_count`）、`MultimodalRetriever`（`retrieve()` 返回纯文本 Document，`retrieveMultimodal()` 返回完整 parts；按 parentDocId 去重）、`ImageEmbedder`（`@FunctionalInterface`，图片向量化可插拔，未注入仅元数据登记）。
- **GraphRAG（sure-ai-rag `com.sure.ai.rag.graph`）**：基于知识图谱的全局主题摘要检索，与向量检索互补。
  - `KnowledgeGraph`（实体 `GraphEntity` / 关系 `GraphRelation`）+ `normalizeName`（去空白、转小写）实体名归一去重，ConcurrentHashMap/CopyOnWriteArrayList 线程安全。
  - `LlmEntityRelationExtractor`：三元组行格式 `主体 | 关系 | 客体`，按行容错解析、脏行跳过；LLM 异常不写入不抛出。
  - `LabelPropagationCommunityDetector`（标签传播社区发现；Louvain 未实现）。
  - `LlmCommunitySummarizer`（聚合社区实体/关系渲染提示词生成主题摘要，异常回退机械拼接）、`GraphRagIndexer`（抽取→社区发现→逐社区摘要管线）、`GraphRagRetriever`（按 query 字面命中实体定位社区，按命中数降序返回社区摘要文档，确定性零额外 LLM 调用）。
- **RAG 评估（sure-ai-rag `com.sure.ai.rag.evaluation`）**：离线评估与跨版本回归。
  - `RagTrace` record（`traceId/question/answer/contexts/referenceAnswer/metadata`，Builder 自动生成 traceId）+ `TraceStore`/`InMemoryTraceStore`（`save/all/findById/size`，`create()/of(List)`）+ `TraceSerializer`（`toJson/toJsonList/fromJson/fromJsonList`）。
  - `LlmJudge`（容错解析 yes/no/分数）为基类的四指标：`FaithfulnessMetric`（faithfulness）、`ContextPrecisionMetric`（context_precision）、`ContextRecallMetric`（context_recall，**无 referenceAnswer 返回 NaN**）、`AnswerRelevancyMetric`（answer_relevancy）。
  - `RagEvaluator`（`llmDefault(client,model)` 一键装配四指标；`evaluate(trace)` 单条 / `evaluateAll(traces)` 按指标跨轨迹取均值；NaN 指标归入 notApplicable 不计入 overall 均值）、`EvaluationResult`（`scores/notApplicable/overall` + `summary()`）、`EvaluationThreshold`（指标名→最低分，Builder `put`）、`EvaluationAssertions.assertMeets`（不达标抛 AssertionError）、`TraceReplay`（`replay/replayAggregate` 从 TraceStore 或 JSON 回放基线）。
- **6 个新向量库适配（sure-ai-rag `com.sure.ai.rag.store`）**：在既有 InMemory/Chroma/Milvus 之上新增 Qdrant、Pinecone、Weaviate（REST+GraphQL）、Elasticsearch、OpenSearch（REST）、Redis（RESP over JDK Socket，非 HTTP）；全部仅依赖 JDK HttpClient/Socket，零官方客户端库。
- **可移植 metadata filter 抽象（sure-ai-rag `com.sure.ai.rag.store.filter`）**：
  - `FilterExpression` 密封接口（permits And/Or/Not/Eq/Ne/In/Gt/Gte/Lt/Lte），静态工厂 `eq(..)/ne(..)/in(..)/gt(..)/gte(..)/lt(..)/lte(..)` + fluent `and(..)/or(..)/not()`。
  - 6 个方言 `FilterTranslator`：Qdrant / Pinecone / Weaviate / Elasticsearch / OpenSearch（均返回 JsonObject）+ Redis（返回 RediSearch filter 字符串，构造时传 numericFields）。
- 新增 docs：`docs/rag.md` 增补高级检索策略 / GraphRAG / RAG 评估章节；`docs/vector-stores.md` 扩为 9 库总览 + 6 新库示例 + metadata filter 专节；README 中英文特性区与文档索引加入口。

### Changed
- `VectorStore` SPI 新增两个带 `FilterExpression` 的 default 重载方法（`similaritySearch(float[],int,FilterExpression)` 与 `similaritySearch(float[],int,double,FilterExpression)`），默认实现忽略 filter 委托旧方法，既有 InMemory/Chroma/Milvus 实现二进制兼容、行为不变；支持过滤的新实现类覆写本方法。

### 测试
- 新增 129 个测试（批次 A 20 + B 17 + C 20 + D1 31 + D2 41），sure-ai-rag 模块累计 242 测试；全工程合计 1114 测试，`mvn -B clean verify` BUILD SUCCESS（checkstyle / spotbugs / jacoco / license 零违规）。

## [1.7.0] - Unreleased

### Added
- **检查点持久化（sure-ai-agent `com.sure.ai.agent.checkpoint`）**：ReActAgent 会话状态可序列化快照，崩溃 / 重启后重放式恢复。
  - `AgentCheckpoint` record（`sessionId / history / iteration / finalAnswer / createdAtEpochMs / metadata`）+ 紧凑 Builder；`history` 防御性拷贝。
  - `CheckpointStore` SPI（`save / load / delete / listSessions`）+ `InMemoryCheckpointStore`（`ConcurrentHashMap`，线程安全，进程内有效）+ `FileCheckpointStore`（本地目录每会话一个 JSON `{dir}/{sessionId}.json`，目录自动创建；`sessionId` 清洗为 `[A-Za-z0-9_-]` 防路径穿越，越界抛 `AiException`）。
  - `CheckpointSerializer` 手写 `ChatMessage ↔ JSON` 映射（仅持久化 `role / content / toolCallId / toolCalls(id/name/argumentsJson)`，多模态 parts 与 reasoningContent 不参与恢复）。
  - `AgentCheckpointer` 门面：`create(sessionId, agent, userMessage)` / `save(store, cp)` / `load(store, sessionId)` / `resume(store, sessionId, agent)`；恢复=把检查点 history 去掉 baseRequest 前缀后的尾部灌入新 `InMemoryConversationMemory`，再 `agent.withMemory(memory).run()`，模型见完整历史不重复动作。
  - `ReActAgent` 新增 9 参构造器（追加 `CheckpointStore + sessionId`）：run 开头（iter=0）/ 每轮工具迭代后（中间快照）/ 结束（带 finalAnswer）自动落盘；两者任一为 null 不启用。
- **流式事件（sure-ai-agent `com.sure.ai.agent.event`）**：把同步 `AgentListener` 回调桥接为结构化事件流，不改 ReActAgent。
  - `AgentEvent` 标记接口（`type() / timestampEpochMs()`）+ 8 个 record：`StepStartedEvent(step.started)` / `StepCompletedEvent(step.completed)` / `ToolCalledEvent(tool.called)` / `ToolCompletedEvent(tool.completed)` / `ThoughtEvent(thought)` / `FinalAnswerEvent(final.answer)` / `TokenDeltaEvent(token.delta，预留)` / `AgentErrorEvent(agent.error)`。
  - `AgentEventSink`（`@FunctionalInterface onEvent`）、`AgentEventPublisher`（`CopyOnWriteArrayList` 多订阅者广播，单订阅者异常隔离）、`StreamingAgentListener implements AgentListener`（桥接 onThought/onToolCall/onToolResult/onFinish/onError/onStepStart/onStepComplete）、`AgentEventSseWriter`（`toSse(event)` 输出 `event:<type>\ndata:<json>\n\n`，`doneMarker()` 输出 `data: [DONE]\n\n`）。
  - `TokenDeltaEvent` 当前不产生（ReActAgent 走非流式 `client.chat`），为将来流式接入预留。
- **HITL 审批（sure-ai-agent `com.sure.ai.agent.approval`）**：工具执行前人工 / 自动审批闸门。
  - `ApprovalRequest`（`requestId/agentId/toolName/arguments/description/highRisk/requestedAtEpochMs`，静态 `of(...)` 自动生成 UUID）、`ApprovalDecision`（`approved()/approved(reason)/rejected(reason)/timeout()`）、`ApprovalStatus` 枚举（`APPROVED/REJECTED/TIMEOUT`）。
  - `ApprovalPolicy`（`@FunctionalInterface requiresApproval(toolName, args)`）4 预置策略：`AllToolsApprovalPolicy.instance()` / `NeverApprovalPolicy.instance()` / `ToolNameApprovalPolicy.of(String...)`（精确匹配大小写敏感）/ `HighRiskApprovalPolicy`（工具名前缀 payment/transfer/withdraw/delete/remove/write/update/exec/shell 或参数 amount/money/balance/price/total 正值，前缀与金额键可自定义）。
  - `ApprovalHandler`（`request(ApprovalRequest) -> ApprovalDecision` + `name()`）4 预置：`AutoApprovalHandler.instance()`（auto）/ `AutoRejectHandler.instance()`+`withReason()`（auto-reject）/ `ConsoleApprovalHandler()`（console，stdout 打印 + stdin 读 y/n，阻塞）/ `TimeoutApprovalHandler(delegate, Duration)`（ForkJoinPool 异步包装，超时 best-effort 取消并返回 TIMEOUT）。
  - `ApprovalGate(policy, handler)` 组合门面：`requiresApproval` 委托策略，`request` 在策略判否时短路直接返回 APPROVED 不触发 handler。
  - `ReActAgent` 11 参构造器追加 `ApprovalGate`：`executeTool` 内审批，REJECTED/TIMEOUT 不执行工具、分别回灌 `用户拒绝执行工具 …：…` / `审批超时，跳过工具 …` 给模型自我修正。
- **长期记忆（sure-ai-agent `com.sure.ai.agent.memory.longterm`）**：跨会话事实沉淀与召回。
  - `MemoryEntry` record（`id/content/metadata/createdAtEpochMs/embedding`，`of(content, metadata)` 自动 UUID，`withEmbedding` 副本）。
  - `MemoryStore` 键值 SPI（`put/get/delete/all/clear`）+ `VectorMemoryStore` 子接口（`search(float[], k)` 余弦 Top-K / `searchByText(query, k)` 子串匹配）+ `InMemoryMemoryStore`（`ConcurrentHashMap`，searchByText 按命中次数降序再按创建时间升序）。
  - `MemoryEmbedder`（`@FunctionalInterface embed`）+ `NoopMemoryEmbedder.instance()`（返回 null 降级文本匹配）；`MemorySummarizer` + `LengthBasedSummarizer`（`DEFAULT_MAX_CHARS=500`，保留首尾截断）；`MemoryExtractor` + `DefaultMemoryExtractor`（跳过 system/tool，长度 ≥ `DEFAULT_MIN_CONTENT_LENGTH=3` 的 USER→「用户说：…」，最后一条 ASSISTANT→「助手答：…」，元数据写 role/sessionId）。
  - `LongTermMemory` 门面（4 组件全传 null 走默认实现）：`remember(messages, sessionId)` / `recall(query, k)` / `forget(id)` / `all()` / `clear()`；recall 路径=向量可用走 `search(vec,k)`、否则 `searchByText`、纯键值退化为最近 k 条。
  - `ReActAgent` 11 参构造器同时注入 `LongTermMemory`：run 前按 `DEFAULT_RECALL_K=3` recall 拼成 system 消息 `相关长期记忆：…` 注入模板之后（不覆盖原 system），run 后 remember 沉淀本轮用户消息与最终答案；与既有会话 `ConversationMemory` 互不干扰。
- 新增 docs：`docs/agent-advanced.md`（四策略快速上手 / 接口表 / 事件类型表 / SSE 格式 / 组合示例）；README 中英文特性区与文档索引加入口。

### Changed
- 纯新增能力：`ReActAgent` 新增 9 参与 11 参构造器，既有 3/6/7 参构造器委托至 11 参版本；所有可选能力（检查点 / 审批 / 长期记忆 / 会话记忆）传 `null` 即关闭，关闭后行为与历史版本逐字节一致。

### 测试
- 新增 48 个测试（批次 1：检查点 16 + 流式事件 16；批次 2：HITL 审批 16 + 长期记忆 16），agent 模块累计 133 测试；全工程合计 985 测试，`mvn -B clean verify` BUILD SUCCESS（checkstyle / spotbugs / jacoco / license 零违规）。

## [1.6.0] - 2026-09-29

### Added
- **AI Gateway（sure-ai-gateway）**：`GatewayClient` implements `AiClient` 对调用方透明（`name()` 返回 `"gateway"`），内部按路由策略选择下游供应商实例，失败时自动故障转移。
  - `ClientRegistry`：多供应商注册（按 `(platform, instanceId)` 二级存储，支持同平台多实例）；注册时显式声明能力集合（默认 `CHAT`/`CHAT_STREAM`）；支持权重（>0）与默认模型名；`byPlatform()`/`byCapability()`/`byName()` 查询；`markUnhealthy()`/`isHealthy()` 健康标记。
  - 6 种路由策略可装饰器组合：`ExplicitRoutingStrategy`（`extra["platform"]` 或模型名 `platform:` 前缀）→ `CapabilityRoutingStrategy`（按 `Capability` 过滤）→ 叶子策略 `RoundRobinStrategy`（AtomicInteger 轮询）/ `WeightedRoutingStrategy`（按权重随机）/ `LowestLatencyStrategy`（滑动窗口平均延迟，`LatencyTracker` 最多 100 样本/5 分钟窗口）/ `LowestCostStrategy`（基于 `PriceCatalog` 输入单价）。
  - 自动故障转移：`FailoverConfig`（`maxAttempts` 默认 3、`unhealthyCooldownMs` 默认 30s、`retryableExceptions` 额外可转移异常、`FailoverListener` 事件回调）；4xx 不转移/5xx 超时转移规则；失败实例摘除冷却后自动恢复。
  - `RequestContext`：`EXTRA_PLATFORM = "platform"`、`EXTRA_TENANT_ID = "tenantId"`；`explicitPlatform()` 按 extra→模型前缀顺序解析；`bareModel()` 去掉平台前缀。
- **密钥池轮转（core + gateway）**：core 新增 `ApiKeyProvider` 接口（`currentKey()`/`nextKey()`/`markBad(String)`/`allKeys()`/`size()`）与 `RoundRobinApiKeyProvider`（原子指针轮询 + 坏 key 冷却默认 60s，冷却到期自动恢复，可注入时钟）；gateway 新增 `KeyRotatingClientDecorator`（`Function<String, AiClient>` 工厂模式 + 按 key `ConcurrentHashMap` 缓存客户端，401/403/429 自动换 key 重试，最多尝试 `provider.size()` 次，可自定义 `Predicate<Throwable>` 触发条件）。
- **租户配额与预算（gateway）**：`TenantManager`（虚拟密钥→租户映射 + 租户→配额配置映射）；`TenantConfig`（`maxCostPerPeriod`/`maxTokensPerPeriod`/`rateLimitQps`/`budgetPeriod` 默认 1 天，0=不限，Builder 模式）；`BudgetEnforcer`（调用前 `checkBeforeCall` 校验成本/token/QPS，调用后 `recordAfterCall` 入账，预算周期滚动窗口自动清零，QPS 复用 sure-core 令牌桶 `RateLimiter`）；`TenantAwareGatewayClient` 装饰器（从 `extra["tenantId"]` 取租户，调用前检查/调用后计量，流式只事前检查不事后计量）；`AiBudgetExceededException`（携带 `tenantId()`）。
- **成本计量（sure-ai-core com.sure.ai.cost）**：
  - `PriceCatalog`：不可变价格目录，内置 18 个主流模型（OpenAI 5 / Anthropic 5 / Gemini 2 / DeepSeek 2 / Qwen 3 / Mistral 1），价格单位 USD/1K tokens，每个模型注释标注来源 URL 与核实日期（2026-09-26）；`withPrice()` 不可变覆盖；`priceFor()`/`hasPrice()`/`size()` 查询。
  - `ModelPrice` record：`(inputPer1k, outputPer1k, cacheReadPer1k, cacheWritePer1k, currency)`，简化构造默认 USD。
  - `CostCalculator`：无状态线程安全；`calculate(model, TokenUsage)` 无缓存；`calculate(model, TokenUsage, cachedReadTokens, cachedWriteTokens)` 含缓存（从 promptTokens 中扣除避免重复计费）；`estimate(model, inputText, estimatedOutputTokens)` 请求前粗估（中文 1.5 字符/token、英文 4 字符/token）。
  - `CostAggregator`：线程安全内存汇总（`ConcurrentHashMap` + `LongAdder`/`DoubleAdder`）；`record(tenantId, model, usage, cost)`；`tenantSummary(tenantId)`/`modelSummary(model)`/`summarySince(epochMillis)`/`reset()`。
  - `CostSummary` record：`(totalCalls, totalPromptTokens, totalCompletionTokens, totalCost, tokensByModel, costByModel)`，`EMPTY` 空快照。
  - `CostMetricsCollector`：实现 `MetricsCollector`，自动把 `onTokenUsage` 回调接入成本计算与汇总；`setCurrentTenantId()`/`clearCurrentTenantId()` ThreadLocal 管理，默认租户 `"default"`。
- **OpenAI 兼容代理（sure-ai-proxy）**：基于 JDK `com.sun.net.httpserver.HttpServer` 的独立 HTTP 服务（零新依赖），把 `GatewayClient` 能力以 OpenAI 兼容 API 暴露。
  - 端点：`POST /v1/chat/completions`（非流式 + SSE 流式 `stream:true`，SSE 格式 `data: <payload>\n\n`，结束 `data: [DONE]\n\n`）；`GET /v1/models`；`POST /v1/embeddings`（路由到声明 `EMBED` 能力且实现 `EmbeddingClient` 的客户端，未注册返回 501）。
  - `VirtualKeyAuth`：`Authorization: Bearer <virtualKey>` 映射到租户 ID，鉴权失败返回 401 OpenAI 错误格式。
  - `ProxyConfig`（`java.util.Properties`）：`proxy.port`（默认 8080）、`proxy.default.model`（默认 gpt-4o）、`proxy.models`（逗号分隔）、`proxy.key.<virtualKey>=tenantId`；`defaults()`/`fromProperties(Properties)`/`load(Path)`。
  - `SureAiProxy`：`start()`/`stop()`/`boundPort()`；`main(String[] args)` 从 properties 文件启动；线程池 `max(2, availProc*2)`。
  - 错误码映射：400（请求体解析失败）/ 401（密钥无效）/ 405（方法不允许）/ 501（无 embeddings 客户端）/ 502（上游错误）。
- 新增 docs：`docs/gateway.md`、`docs/cost.md`、`docs/proxy.md`；examples 新增 `GatewayDemo`（离线 fake client 演示轮询路由+故障转移）与 `ProxyDemo`（JDK HttpClient loopback 自测）；README 中英文特性区与文档索引加入口。

### Changed
- 纯新增，未改公共 API。

### 测试
- 新增 82 个测试：成本计量 23（PriceCatalog/CostCalculator/CostAggregator/CostMetricsCollector）+ gateway 核心 22（ClientRegistry/GatewayClient/6 种策略/RequestContext）+ 密钥/租户 22（ApiKeyProvider/KeyRotatingClientDecorator/TenantManager/TenantConfig/BudgetEnforcer/TenantAwareGatewayClient/AiBudgetExceededException）+ 代理 15（SureAiProxy/ProxyConfig/VirtualKeyAuth/OpenAiProtocol/端点回环）。

## [1.5.0] - 2026-09-26

### Added
- **MCP Server 模块（sure-ai-mcp-server）**：新增模块把 sureai 多平台能力反向暴露为 MCP server，任何 MCP 客户端（Claude Desktop、Cursor、IDE）一个连接即可调用。核心只依赖 sure-ai-mcp + sure-ai-core，零新依赖、零平台模块硬依赖。
  - 协议层：复用 sure-ai-mcp JSON-RPC 2.0 消息模型，兼容有状态规范（2025-06-18，initialize 握手 + notifications/initialized + Mcp-Session-Id）与无状态规范（2026-07-28 实验性，params._meta 读取协议版本、server/discover 能力广告、工具列表 ttlMs/cacheScope），两种形态按请求内容自适应。
  - 双传输：`StdioMcpServerTransport`（System.in/stdout NDJSON 行帧，64MB 帧上限，独立 daemon 读线程）；`HttpMcpServerTransport`（JDK 内置 com.sun.net.httpserver.HttpServer，单端点 /mcp 支持 application/json 直返与 text/event-stream SSE，Mcp-Session-Id 维护，端口可配置）。
  - 注册式工具模型：`McpServerTool`（name/description/inputSchema/handler）+ `McpServer` 注册表（CopyOnWriteArrayList），支持 tools/list、tools/call（content[].text、isError 正确返回）、resources/prompts 可扩展空能力。
  - 预置工具工厂 `SureAiTools`：chatTool/embedTool/imageTool，支持单 AiClient 与 Map<String,AiClient> 多客户端注册表路由；inputSchema 由 JsonSchemaGenerator 自动生成；RAG 查询工具设计为 sure-ai-rag 适配类（不在 mcp-server 核心）。
  - 静态入口 `McpServerUtil`（SingletonHolder 单例，init/server/resetServer/startStdio/startHttp）。
  - 新增 25 个测试（协议引擎 12、SureAiTools 8、Stdio 管道回环 e2e 2、HTTP 本地回环 3），覆盖 initialize→tools/list→tools/call 完整流程、chat/embed/image 路由与错误→isError、超大帧拒绝、无状态直连。
  - 新增 docs/mcp-server.md（stdio/HTTP 快速上手、Claude Desktop 配置 JSON、自定义工具注册、多客户端注册表）；examples 新增 McpServerDemo（stdio）与 McpHttpServerDemo；README 中英文特性区与文档索引加入口。
- **JsonSchemaGenerator（sure-ai-core）**：core 新增 `com.sure.ai.util.JsonSchemaGenerator`，零依赖反射生成 JSON Schema（Draft 2020-12）。静态无状态线程安全，API：`generate(Class<?>)` → JsonObject（根带 $schema）、`generateString(Class<?>)` → 紧凑 JSON、`generateStringPretty(Class<?>)` → 2 空格美化。支持 String/char→string、byte/short/int/long→integer、float/double/BigDecimal/BigInteger→number、boolean→boolean、Enum→string+enum 数组、List/Set/Collection/数组→array+items（按泛型实参解析元素）、Map→object+additionalProperties、POJO/record→object+properties+required、Instant/LocalDateTime/Date→string+format=date-time、LocalDate→date、UUID→uuid、URI/URL→uri。required 策略：unboxed 原始类型必填，引用类型可选。字段发现：record 用 getRecordComponents()，POJO 沿父类链收集 getDeclaredFields()（跳过 static/transient/synthetic，子类同名遮蔽优先）。循环引用：递归携带祖先类链 Set，再次命中返回 {"type":"object"} 空对象。新增 13 个测试逐关键字断言。供 MCP server 自动生成 tool inputSchema。

### Changed
- **5 平台 capabilities() 精确声明（v1.4.0 遗留）**：对照官方文档 + 实际代码核实，OpenAI/Azure/Doubao/Qwen/Zhipu 从继承基类全量 9 项改为精确声明：
  - OpenAiClient：全量 9 项（显式覆写，参考平台；Sora 消费产品 2026-04-26 停服但库实现 /videos 协议且有测试，保守保留）。
  - AzureClient：6 项（CHAT/CHAT_STREAM/EMBED/IMAGE/MODERATION/FINETUNE），移除 VIDEO/TTS/STT（走独立 AzureVideoClient/AzureTtsClient/AzureSttClient）。
  - DoubaoClient：5 项（CHAT/CHAT_STREAM/EMBED/IMAGE/MODERATION），移除 VIDEO/FINETUNE/TTS/STT（IMAGE 经火山方舟文档确认 OpenAI 兼容 /images/generations；MODERATION 保守保留未核实）。
  - QwenClient：6 项（CHAT/CHAT_STREAM/EMBED/TTS/STT/MODERATION），移除 IMAGE/VIDEO/FINETUNE（TTS/STT 为本类原生覆写；IMAGE/VIDEO 走独立 QwenImageClient/QwenVideoClient；MODERATION 保守保留）。
  - ZhipuClient：7 项（CHAT/CHAT_STREAM/EMBED/IMAGE/TTS/STT/MODERATION），移除 VIDEO/FINETUNE（IMAGE/TTS/STT 已接线且有测试；MODERATION 保守保留）。
  - 判定规则：独立能力 Client（*VideoClient/*ImageClient/*TtsClient/*SttClient 等）均 extends AbstractAiClient 不走 OpenAiCompatClient 的 guard，因此某能力若由独立 Client 承载，主 Client 不声明该能力（主 Client 对应方法因 guard 抛 AiException，引导用户改用独立 Client）。每平台新增 testCapabilitiesDeclaration() 测试（反射读 protected capabilities() + 断言集合精确 + 对移除的受 guard 能力断言调用抛 AiException），共 +5 测试。

### 模块注册
- 新模块 sure-ai-mcp-server 注册到父 pom.xml <modules>（sure-ai-mcp 之后）、sure-ai-bom/pom.xml dependencyManagement、sure-ai-all/pom.xml dependencies。

## [1.4.0] - 2026-09-26

### Added
- **P2-6 Client 能力面收口**：core 新增 `Capability` 枚举（14 值）与 `AbstractAiClient.protected capabilities()/guard(Capability)/name()` 契约；`OpenAiCompatClient` 声明全量 9 项能力并在 embed/image/video/moderation/finetune 入口加 guard；DeepSeek/Grok（chat+stream）、Mistral（chat+stream+embed+finetune）、LlamaCpp/Moonshot（chat+stream+embed）逐平台审计声明；不支持的方法在发请求前抛清晰 `AiException("<slug> does not support <CAP> capability")`；新增 `CapabilityGuardTest` 5 个测试。
- **P2-17 测试分类**：工程为 JUnit4.13.2，采用 `@Category` 方案；core 新增 `com.sure.ai.internal.test.tag` 包下 `Unit`/`Slow`/`E2e` 标记接口；`LruCacheStoreTest`/`CircuitBreakerIntegrationTest` 标 Slow，`McpClientE2ETest` 标 E2e；父 pom 新增 `fast` profile（excludedGroups 排除 Slow/E2e）；全量 812 vs fast 801，精确排除 11 个。
- **P2-18 CI 矩阵 + 发布 workflow**：`ci.yml` 改为 OS 矩阵（JDK21 跑 ubuntu+macos，JDK25 仅 ubuntu，windows 可选 continue-on-error）；新增 `release.yml`（tag 触发 + workflow_dispatch，需 secrets：OSSRH_USERNAME/OSSRH_PASSWORD/GPG_PRIVATE_KEY/GPG_PASSPHRASE）。
- **P2-19 benchmark 自动化**：新增 `benchmark.yml`（workflow_dispatch + 每月 1 号 cron，JMH 执行并归档 `benchmark-result.json`）。

### Changed
- **P2-9 AbstractAiClient 拆分**：682 行上帝类按职责拆分为 3 个包内可见协作类——`RetryExecutor`（284 行，重试/熔断/限流/指标/错误映射）、`RequestBuilder`（131 行，请求构建+鉴权/签名钩子链）、`MultipartBodyBuilder`（81 行，multipart 拼装）；`AbstractAiClient` 收敛为 368 行薄委托层；18 个 protected/public 成员签名逐字不变；Bedrock `signRequest` 虚分派通过 RequestBuilder 持 client 引用保留；子类零修改。
- **P2-10 OpenAiCompatClient 拆分**：841 行按能力域拆分为 10 个包内可见策略类——`ChatCompatStrategy`(328)/`VideoCompatStrategy`(139)/`AudioCompatStrategy`(145)/`FineTuneCompatStrategy`(109)/`StreamCompatStrategy`(93)/`ModerationCompatStrategy`(90)/`ImageCompatStrategy`(89)/`EmbeddingCompatStrategy`(84)/`CompatPost`(34)/`CompatJson`(43)；主类收敛为 340 行薄编排层；公共 API 签名逐字不变，10 个平台 Client 零修改；流式 SSE 复用基类 `doPostStream` 内置 `SseLineReader`。
- **P2-2 Util 单例样板抽取**：core 新增 `SingletonHolder<T>` 通用 DCL 单例容器（152 行，get/set/reset/getOrCreate/isInitialized）；17 个平台 Util（Agent/Anthropic/Azure/Baidu/Bedrock/Cohere/DeepSeek/Doubao/Gemini/Grok/LlamaCpp/Mistral/Moonshot/Ollama/OpenAi/Qwen/Zhipu）从 volatile+LOCK+synchronized 样板改为委托 SingletonHolder；Util 主源码净减 493 行（多子客户端类收益最大：Azure/Doubao 各 -70、Qwen -69、Zhipu -56）；公共 API 签名零变更；`AgentUtil.resetRegistry` 特殊映射为 set(new ToolRegistry())；Realtime 客户端用 getOrCreate 带参懒加载；新增 `SingletonHolderTest` 8 个测试。
- **P2-5 跨平台复制片段抽取**：`AiConfig` 新增 `withBaseUrlIfAbsent(String)` 统一入口；删除 openai/deepseek/grok/llamacpp/mistral/qwen 共 10 处平台 Client 中逐字复制的 `applyDefaultBaseUrl` 静态方法（约 108 行）。

### Fixed
- **P2-13 examples 冗余依赖**：移除 `sure-ai-examples/pom.xml` 中冗余的 `sure-ai-agent` 依赖（`sure-ai-all` 已传递引入）。
- **前置重构连带破损修复**：`RateLimitTest` 反射从 `AbstractAiClient.rateLimiter` 迁移为两跳 `retryExecutor.rateLimiter`；18 个白盒测试反射目标从 `client/videoClient` 改为 `HOLDER/MAIN/VIDEO`（SingletonHolder 迁移）；`AzureVideoClientTest` 迁移到 SingletonHolder；清理多处未用 import。

### 不适用项（逐项核对）
- **P2-3 默认模型常量统一**：全库无散落 DEFAULT_MODEL，各 `*Models.java` 是模型 ID 常量目录，仅 Bedrock 有默认模型且已集中；新增默认模型会改变运行时行为，按约束不动。
- **P2-7 JavaDoc 完整度**：SSRFGuard/SingletonHolder/CohereRerankClient 类级 JavaDoc 均完整，`McpResponse.fromJson` 已有 @return，满足。
- **P2-8 starter 包名**：现为 `com.sure.ai.boot`，改名为 Breaking Change 且已稳定，保留。
- **P2-14 Bedrock IOException 粒度**：v1.3.0 已接入基类，IOException 自动映射为 AiTimeoutException，已完成。
- **P2-21 README 平台口径**：中英文 README 均为"16 平台"，一致。
- **P2-22 benchmark 不进默认 verify**：`sure-ai-benchmark/pom.xml` 已配 skipTests/jacoco.skip/spotbugs.skip，已完成。

## [1.3.0] - 2026-09-25

### Added
- **P1-12 Cohere Rerank**：新增 `CohereRerankClient`（`extends AbstractAiClient implements RerankClient`），对接 Cohere v2 `/rerank` API（Bearer 鉴权、documents 字符串列表、top_n 可选、results index/relevance_score/document 映射到 core RerankResponse）；`CohereUtil` 新增 `rerankClient()` 单例与 `rerank()` 静态入口；`CohereModels` 新增 `RERANK_V3_5` 常量；5 个 mock 测试；docs/cohere.md 与 README 能力矩阵 Cohere Rerank 列 ❌→✅。
- **P1-13 四平台 Demo**：examples 新增 `CohereDemo`、`GrokDemo`、`LlamaCppDemo`、`MistralDemo`（与现有 Demo 同风格，零真实密钥可编译，缺 key 优雅提示），`ExamplesRunner` 注册 4 个新 case。
- **P1-8 Spring Starter 补 5 平台**：`SureAiProperties` 新增 grok/mistral/llamacpp/cohere（PlatformProperties）+ bedrock（BedrockProperties）属性块；`SureAiAutoConfiguration` 新增对应 5 个 @Bean（含 Bedrock accessKey/secretKey/sessionToken/region/model 配置）；`BedrockProperties` 新类。
- **P1-9 PlatformProperties 字段对齐**：新增 connectTimeout/proxy/organization/rateLimitQps/cacheTtl/extraHeaders/secretKey 共 7 字段；`buildConfig()` 同步映射；对象型扩展点（cacheStore/circuitBreaker/retryListeners/metricsCollector）JavaDoc 说明需编程式 @Bean 注入。

### Changed
- **P0-2 Bedrock 接入基类**：`BedrockClient` 从 `implements AiClient` 改为 `extends AbstractAiClient implements AiClient`，复用基类的重试/限流/熔断/指标/代理/超时能力；core `AbstractAiClient` 新增通用 `signRequest(method, url, body)` 受保护钩子（默认 no-op，不为 Bedrock 单点开洞），接入 newRequest/buildGetRequest/buildMultipartRequest 三处请求构建；Bedrock `chat()` 走 `doPostRaw`、`chatStream()` 走 `doPostStream`；删除自建 HttpClient、硬编码超时、newRequestBuilder、ensureSuccess；IOException 自动升级为 `AiTimeoutException`。
- **P1-11 BedrockUtil 静态入口**：补齐 `init(ak,sk,region)` / `init(ak,sk,token,region,modelId)` / `init(BedrockClient)` / `client()` / `resetClient()` / `chat(model,prompt)` / `chat(request)` / `chatStream(...)`，与其余 15 平台范式一致；保留 `create()` 显式工厂。
- **P1-2 百度密钥出 URL**：BaiduClient/BaiduImageClient OAuth 换 token 从 URL 查询串改为 POST body（`application/x-www-form-urlencoded`，client_id/client_secret 经 URLEncoder）；业务 API 从 `?access_token=` 改为 `Authorization: Bearer` 头（重写 `applyAuth`，基类 doPost/doPostStream 自动回调）；8 处路径移除 access_token；TTS/STT token 本就在 body 不在 URL，保持原样。
- **P1-10 Baidu secretKey Spring 配置**：PlatformProperties 新增 `secretKey` 字段；`SureAiAutoConfiguration.baiduClient()` 把 secret-key 写入 `extraHeader("secretKey", ...)`（BaiduClient 从该 header 读取）。
- **P2-4 路径参数 URL encode**：core `AbstractAiClient` 新增 `encodePathSegment()` 工具方法；OpenAiCompatClient/OpenAiBatchClient/Azure*/DoubaoVideo/ZhipuVideo/Qwen* 共 11 处 taskId/jobId/batchId/generationId 路径拼接改为编码。
- **P2-11 DefaultRealtimeConnector 资源泄漏**：HttpClient 从每次 connect() 新建提升为 final 字段复用，专用 daemon Executor，实现 `AutoCloseable`；`AbstractRealtimeClient.close()` 关闭 connector。
- **P2-12 AgentOrchestrator AutoCloseable**：实现 `AutoCloseable`，新增 `ownsExecutor` 标志，`close()` 只 shutdownNow 内部创建的线程池（外部注入的不动）。
- **P2-15 绕过基类流式路径 JavaDoc 标注**：AnthropicClient.streamMessages（命名 SSE）、OllamaClient.streamChat（NDJSON）、BaiduClient.postForm/postJson/getAccessToken（TTS/STT/OAuth 独立端点）均加"不经过基类重试/熔断/指标"标注；Baidu chatStream 已正确走基类 doPostStream。
- **P2-20 flaky 测试修复**：CircuitBreakerIntegrationTest `Thread.sleep(300L)` 改为轮询 cb.state() 直到 HALF_OPEN（5s 上限）；LruCacheStoreTest 三个 TTL 测试固定 sleep 改为 `waitUntilExpired()` 轮询辅助（10ms 间隔，5s 上限）。

### Fixed
- **P1-7 零断言测试修复**：DoubaoStt/Tts/VideoClientTest 的 `testUtilReset()` 从仅一行 reset 改为反射注入→reset→assertNull 断言字段置 null；AzureVideo/BaiduImage/QwenImage/QwenVideo 测试的 `assertEquals(0,0)` 无意义断言同样修复。
- **P1-14 异常体系统一**：DeepSeekClient.embed 从 `UnsupportedOperationException` 改为 `AiException("DeepSeek does not provide embeddings API")`；测试断言类型同步更新。
- **P1-6 CI 覆盖率通配**：`.github/workflows/ci.yml` 覆盖率 artifact 从逐模块列举 12 个改为通配 `sure-ai-*/target/site/jacoco/`，覆盖全部 21 个有报告模块。
- **P2-16 SSE 边界测试**：SseLineReaderTest 从 6 个增至 11 个，新增 `[DONE]` 标记、malformed JSON 原样投递、空响应、仅注释、chunked 分块重组（3 字节 read 模拟）5 个测试。

- 全量 27 模块 799 测试全绿（+29），行覆盖率 core 86.0%（≥0.70）、其余模块均 ≥0.60，checkstyle/spotbugs/license 零违规。

## [1.2.1] - 2026-09-25

### Fixed
- **P0-1 配置字段静默丢失**：`AiConfig` 新增 `withBaseUrl(String)` 实例方法，返回包含全部 14 个字段的新配置（apiKey/baseUrl/timeout/connectTimeout/proxy/organization/extraHeaders/maxRetries/retryListeners/metricsCollector/rateLimitQps/cacheStore/cacheTtl/circuitBreaker），仅替换 baseUrl。替换 OpenAi/DeepSeek/Grok/LlamaCpp/Mistral/Qwen 等 6 处 `applyDefaultBaseUrl` 静态方法及 Azure/Baidu/Doubao/Cohere/Moonshot/Zhipu 等平台的 `rebuild/withDefaults/withDefaultBaseUrl` 样板（共 19 个源文件），全部改为委托 `withBaseUrl`，消除用户配置的 metrics/retryListeners/限流/缓存/熔断字段在补默认 baseUrl 时静默丢失的问题。新增 `AiConfigWithBaseUrlTest` 6 个测试逐字段断言。
- **P1-3 MCP NDJSON 帧大小上限**：`LineFrameMcpTransport` 新增 `MAX_LINE_BYTES=64MB` 常量与 `LineLimitedInputStream`，每行读取超过 64MB 抛 IOException 并关闭传输，防止恶意 MCP server 持续写字节不发 `\n` 导致 OOM。新增包级 4 参构造器便于测试注入小上限。
- **P1-4 MCP 子进程强杀**：`StdioMcpTransport.doClose()` 改为 `destroy()` → `waitFor(5, SECONDS)` → 超时 `destroyForcibly()` 并再 `waitFor`，消除子进程忽略 SIGTERM 时 `close()` 永久阻塞的问题。
- **P1-5 MCP 断连测试**：补充读超时（对端永不回复）、对端异常断开（EOF）、超大帧关闭传输三类测试，以及忽略 SIGTERM 子进程强杀测试（`sh -c "trap '' TERM; sleep infinity"`），共 5 个新测试。

### Security
- **P0-3 Milvus filter 注入**：`MilvusVectorStore.delete()` 中 id 直接拼进 Milvus 过滤表达式 `"id in [\"" + id + "\"]"`，恶意 id（如 `x" or "1"="1`）可逃逸字符串字面量匹配全表导致误删。新增 `escapeFilterString` 私有方法转义反斜杠（`\`→`\\`）与双引号（`"`→`\"`），delete 方法使用转义后的 id。新增 3 个注入测试（恶意双引号/反斜杠/正常 id 回归）。
- **P1-1 HttpTool SSRF + 响应体 OOM**：新增 `SSRFGuard` 工具类（纯 JDK `InetAddress`，零新依赖），在 `HttpTool.execute` 中对目标 URL 的 host 解析全部 IP 后校验，拒绝回环（127.0.0.0/8、::1）、私有段（10/8、172.16/12、192.168/16、fc00::/7）、链路本地（169.254.0.0/16、fe80::/10，含云元数据 169.254.169.254）、0.0.0.0/8、多播地址及 IPv4-mapped IPv6 嵌入内网地址（`::ffff:127.0.0.1`），DNS 轮询任一 IP 危险即拒绝。响应体从 `BodyHandlers.ofString()` 全量入内存改为 `BodyHandlers.ofInputStream()` 流式限长读取（`maxChars * 4` 字节上限，UTF-8 最坏情况），超过立即中止并截断，防止恶意服务器发超大响应导致 OOM。`HttpTool.Builder` 新增 `ssrfProtection(boolean)` 选项（默认 true，内网部署可关）。新增 `SSRFGuardTest` 12 个测试 + HttpTool 默认 SSRF 拦截测试 1 个。

### Docs
- **P2-1 版本徽章与依赖片段过期**：`README.md` / `README.en.md` 中 Maven Central 徽章从 `0.1.0` 更新为 `1.2.1`，12 处 Maven 依赖片段 `<version>0.1.0</version>` 同步更新；`docs/platforms/` 下 11 个平台文档的 `<version>0.1.0-SNAPSHOT</version>` 更新为 `1.2.1-SNAPSHOT`；`.github/ISSUE_TEMPLATE/bug_report.md` 模板版本同步。全仓搜索确认无其他过期 0.1.0 硬编码（CHANGELOG 历史记录除外）。

- 全量 27 模块 770 测试全绿（+27），行覆盖率 core 86.4%（≥0.70）、其余模块均 ≥0.60，checkstyle/spotbugs/license 零违规。

## [1.2.0] - 2026-09-25

### Added
- **MCP 客户端（sure-ai-mcp）**：新增基础设施模块 `sure-ai-mcp`——Model Context Protocol JSON-RPC 2.0 客户端，消息层（request 递增 id / notification 无 id / 批量可选）、initialize 握手（protocolVersion "2025-06-18" + client capabilities + notifications/initialized）、正常方法调用与 close。传输抽象 `McpTransport` + 两种实现：`StdioMcpTransport`（ProcessBuilder 子进程 stdin/stdout NDJSON 行帧）、`StreamableHttpMcpTransport`（JDK HttpClient POST 单端点，application/json 直返 / text/event-stream SSE 聚合，维护 Mcp-Session-Id）。能力 API：tools/list、tools/call（解析 content[].text 与 isError）、resources/list、resources/read、prompts/list、prompts/get。**McpTool 适配器**：把 MCP tool 包装为 `ToolHandler`，`registerAllTools` 批量注册进 `ToolRegistry`，可与 ReActAgent 无缝组合。静态入口 `McpUtil`。20 个测试，行覆盖率 80.4%。文档 `docs/mcp.md`，示例 `McpDemo`。
- **AWS Bedrock 平台（sure-ai-bedrock）**：新增平台模块 `sure-ai-bedrock`——纯 JDK 实现 AWS Signature V4 签名（`AwsSigV4Signer`，canonical request / string-to-sign / 链式 HMAC 密钥派生 / Authorization 头拼装，含完整 JavaDoc，通过 AWS 官方 iam 测试向量 kSigning=c4afb1cc…154a4b9 校验）。使用 Bedrock 统一 Converse API：非流式 POST /model/{modelId}/converse（system/messages/inferenceConfig → output.message.content/stopReason/usage 映射到 core ChatResponse/TokenUsage）；流式 POST /converse-stream（SSE 事件 messageStart/contentBlockDelta/messageStop/metadata，接入 core 流式回调 Consumer<ChatStreamChunk>）。凭证支持显式传入与环境变量（AWS 标准 AWS_ACCESS_KEY_ID 等 + SURE_AI_BEDROCK_* 覆盖），缺凭证抛清晰异常。四件套结构 BedrockClient/BedrockModels/BedrockUtil/package-info。22 个测试，行覆盖率 89.5%。文档 `docs/bedrock.md`，示例 `BedrockDemo`。Titan Embeddings 暂未接入（embed 抛 AiException，文档注明）。
- 全量 26 模块 743 测试全绿（+42）。

## [1.1.0] - 2026-09-24

### Added
- **Agent 深化——PlanExecuteAgent 完整实现**：重写骨架为完整 Plan-and-Execute 编排器（规划→逐步执行→汇总），三级计划解析兜底（JSON 数组→按行→单步直接回答），maxSteps+总超时双防护，单步失败重试一次后记录继续；AgentListener 新增 onPlanGenerated/onStepStart/onStepComplete 三个 default 方法（向后兼容）。
- **Agent 深化——会话记忆 Memory**：新包 `com.sure.ai.agent.memory`——`ConversationMemory` 接口 + `InMemoryConversationMemory`（环形窗口默认 20 条，synchronized 线程安全）；ReActAgent/PlanExecuteAgent 可选注入 memory（null 时行为与旧版逐字节一致），请求时注入历史、回合结束后记录 user+assistant。
- **Agent 深化——多 Agent 编排**：新包 `com.sure.ai.agent.orchestrator`——`TaskSplitter`/`SimpleTaskSplitter`（段落/句子/均分策略）、`ResultAggregator`/`ConcatenatingAggregator`、`AgentOrchestrator`（ExecutorService 并行执行+invokeAll 超时+异常隔离不拖垮整体）。
- **Agent 深化——内置工具包**：新包 `com.sure.ai.agent.tool.builtin`——`HttpTool`（JDK HttpClient GET/POST，scheme 白名单+响应截断）、`DateTimeTool`（format+zone）、`CalculatorTool`（自研递归下降解析器，白名单 +-*/()，禁止 eval/反射）。
- 新增 43 个测试（agent 模块 29→72），agent 行覆盖率 83.6%。
- **RAG 生产化——外部向量库适配**：`sure-ai-rag` 新增 `MilvusVectorStore`（Milvus 2.x REST v2，`/v2/vectordb/entities/insert|search|delete`，Bearer 鉴权，COSINE/L2/IP 距离映射）与 `ChromaVectorStore`（Chroma REST v1，`/api/v1/collections/{id}/add|query|delete`，X-Chroma-Token，构造时 get-or-create），均 `implements VectorStore`，JDK HttpClient 零第三方依赖，本地 HttpServer mock 测试 9 个。pgvector 以文档可复制 JDBC 示例提供（不引驱动）。文档 `docs/vector-stores.md`。
- **RAG 生产化——Prompt 模板与查询改写**：新包 `com.sure.ai.rag.prompt`——`PromptTemplate`（`{var}`/`{var=default}` 占位符、严格模式、fromResource、varargs render）、`ChatTemplate`（多消息模板 + few-shot 示例组装）；新包 `com.sure.ai.rag.rewriter`——`QueryRewriter` 接口 + `ModelQueryRewriter`（AiClient 生成多查询，按行解析，异常回退原查询），不注入 RagPipeline 默认链路（向后兼容），文档说明手动融合方式。新增 33 个测试，rag 行覆盖率 88.4%。文档 `docs/prompt-template.md`。
- **响应缓存（core）**：`com.sure.ai.client.cache` 包——`CacheStore` SPI（get/put/remove/clear，TTL）、`ChatCacheKey` 请求归一化 SHA-256（参与字段：model/messages/temperature/topP/tools/responseFormat/reasoningEffort/thinkingConfig/grounding；不含 maxTokens/n/stream/extraHeaders，tools 按 name 排序保证顺序无关）、`LruCacheStore` 内置实现（LinkedHashMap accessOrder + synchronized，容量/TTL 可配，惰性过期）。`AiConfig` 新增 `cacheStore`/`cacheTtl`（默认 null=关闭，零开销）。`OpenAiCompatClient.chat()` 非流式请求查缓存，命中直接返回（不触发网络/指标/重试），未命中走原逻辑并回写；流式/错误不缓存。新增 19 个测试，core 行覆盖率 84.2%。文档 `docs/cache.md`（含 Redis CacheStore 可复制示例）。
- **Spring Boot Starter**：新模块 `sure-ai-spring-boot-starter`（`com.sure.ai.boot` 包）——`SureAiProperties`（`@ConfigurationProperties(prefix="sure.ai")`，11 平台嵌套属性 + rag/agent 预留）、`SureAiAutoConfiguration`（`@AutoConfiguration`，11 平台 `@Bean` + `@ConditionalOnMissingBean` + `@ConditionalOnProperty(name="api-key")`，缺 key 不装配）、`AutoConfiguration.imports` 注册。`spring-boot-autoconfigure` 仅本模块 compile，不进 sure-ai-all、不进运行期依赖链，core/平台模块零 Spring 依赖。5 个 `ApplicationContextRunner` 测试。文档 `docs/spring-boot.md`。
- 开发治理：引入「软件研发小组」团队配置（`.team/`：manifest + team.yml + a1..a5 角色定义），新增 `docs/TEAM.md` 说明协作模式与迭代角色链（统筹 → 需求/验收 → 架构 → 实现 → 独立质检），README 中英文同步。

- **可靠性——熔断器**：新包 `com.sure.ai.client.resilience`——`CircuitBreaker` 三态状态机（CLOSED→OPEN→HALF_OPEN），滑动窗口失败计数（默认5次内3次失败）+ OPEN超时（默认10s）+ HALF_OPEN探测（默认1次），ReentrantLock线程安全，Snapshot不可变快照；`AiConfig.circuitBreaker` 可选注入，默认关闭零开销；`AbstractAiClient.executeWithRetry` 最外层包裹，OPEN时快速失败不发网络/不触发重试/指标/限流，与重试嵌套协作。新增12测试，core行覆盖率85.7%。文档 `docs/circuit-breaker.md`。
- **性能基准——JMH Benchmark**：新模块 `sure-ai-benchmark`（test scope，不进all/运行期链），jmh-core 1.37，4个基准类（JsonBenchmark/ChatRequestBenchmark/OpenAiCompatClientBenchmark/SseParseBenchmark），jacoco/spotbugs skip，默认verify不执行。文档 `docs/benchmark.md`。
- **新平台——Grok (xAI)**：`sure-ai-grok`，OpenAI兼容（base https://api.x.ai/v1），chat/stream/models，无Embeddings（embed抛AiException），reasoning_effort透传，10测试，行覆盖率73.2%。
- **新平台——Mistral**：`sure-ai-mistral`，OpenAI兼容（base https://api.mistral.ai/v1），chat/stream/embed/models，10测试，行覆盖率70.2%。
- **新平台——Cohere v2**：`sure-ai-cohere`，独立协议（base https://api.cohere.com/v2），POST /chat（响应message.content[].text，无choices）、POST /embed（input_type必填，响应embeddings.float）、SSE命名事件流（content-delta/message-end，无[DONE]），无models列表API，4测试，行覆盖率61.8%。
- **新平台——llama.cpp**：`sure-ai-llamacpp`，OpenAI兼容本地服务器（base http://localhost:8080/v1，可选鉴权），chat/stream/embed/models，10测试，行覆盖率92.7%。
- 全量24模块701测试全绿（+46）。
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
