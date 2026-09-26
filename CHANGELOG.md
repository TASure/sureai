# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.0] - Unreleased

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
