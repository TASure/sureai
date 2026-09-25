# sureai v1.2.0 全维度团队检视报告

> **检视版本**：v1.2.0（已发布，当前工作树 `1.3.0-SNAPSHOT`，HEAD `e93ff96`）
> **检视日期**：2026-09-25
> **检视模式**：软件研发小组团队模式（a2 产品 / a3 架构 / a5 质检 独立产出，a1 统筹验收）
> **检视方法**：全量 `mvn -B clean verify` 实跑（BUILD SUCCESS，5:33）、26 模块逐文件源码审查、jacoco/checkstyle/spotbugs/license 门禁报告核对、竞品事实网络搜索核实、测试含金量抽查
> **工程规模**：26 模块（16 平台 + 10 基础设施）、282 源文件、102 测试文件、全量 743 测试

---

## 一、检视范围与方法

### 1.1 检视维度

| 角色 | 维度 | 核心方法 |
|---|---|---|
| a2 产品经理 | 能力矩阵完整性、竞品对比、易用性、文档覆盖度 | 逐平台 Client 源码审查能力实现深度、general_search 核实竞品事实、README/docs/examples 对照 |
| a3 架构师 | 模块依赖方向、core SPI 完备性、扩展性、配置一致性、异常体系、线程/资源管理、all 聚合洁净度 | 逐模块 pom.xml 依赖分析、核心类源码审查、接口实现清单、生命周期追踪 |
| a5 质检官 | 代码质量（门禁/重复/命名/JavaDoc/硬编码/并发/安全/license）、测试回归（覆盖率含金量/异常路径/mock 偏差/flaky/CI 矩阵/benchmark） | checkstyle/spotbugs 实跑、jacoco.xml 逐模块核算、测试方法抽查、grep 扫描、CI yml 审查 |

### 1.2 问题分级定义

- **P0**：影响正确性/安全/数据丢失/必然崩溃，或阻断用户正常集成——必须优先整改
- **P1**：重要能力缺口、架构债、测试盲区——近期版本整改
- **P2**：体验、规范、可维护性改进——排期跟进

### 1.3 门禁实测基线

| 门禁 | 实测结果 |
|---|---|
| `mvn -B clean verify` | BUILD SUCCESS（26 模块全过，743 测试 0 失败） |
| checkstyle | 0 violation（26 模块） |
| spotbugs (effort=Max/threshold=Medium) | 0 BugInstance（20 扫描模块） |
| license 头 | 282/282 主源文件含 Apache-2.0 头 |
| jacoco 行覆盖率 | core 85.7%（门 0.70）；其余 61.8%–100%，全部 ≥0.60 |

> 门禁全部通过。本报告聚焦"通过表象下"的架构债、安全面、测试盲区与体验问题。

---

## 二、能力矩阵（16 平台 × 18 能力）

> 判定口径：✅完整实现 / 🟡部分或受限 / ⚠️方法存在但抛异常占位 / ❌不支持。
> 架构前提：`OpenAiCompatClient`（core，841 行）一次性实现了 chat/chatStream/embed/image/video/tts/stt/models/moderation/finetune 全套 OpenAI 协议族。10 个平台（openai/azure/deepseek/doubao/grok/llamacpp/mistral/moonshot/qwen/zhipu）`extends OpenAiCompatClient` 自动获得全套；6 个自定义协议客户端（anthropic/gemini/baidu/cohere/ollama/bedrock）逐个手写。

| 平台 | chat | chatStream | embed | rerank | image | video | audio | realtime | structured | multimodal | thinking | grounding | finetune | moderation | models | batches | cache | fn-calling |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| OpenAI | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅自动 | ✅ |
| Azure | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅自动 | ✅ |
| Anthropic | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅cache_control | ✅ |
| Gemini | ✅ | ✅ | ✅ | ❌ | ✅ | 🟡Veo需OAuth | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ | ✅cachedContent | ✅ |
| DeepSeek | ✅ | ✅ | ⚠️UOE | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | 🟡仅回传 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| 通义 Qwen | ✅ | ✅ | ✅ | ✅ | ✅异步 | ✅异步 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌控制台 | ❌ | ✅ | ❌ | ❌ | ✅ |
| 智谱 Zhipu | ✅ | ✅ | ✅ | ❌ | ✅ | ✅异步 | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌控制台 | ❌ | ⚠️ | ✅ | ❌ | ✅ |
| Moonshot | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| 豆包 Doubao | ✅ | ✅ | ✅ | ❌ | ❌ | ✅异步 | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌控制台 | ❌ | ⚠️ | ❌ | ❌ | ✅ |
| 百度千帆 | ✅ | ✅ | ✅ | ❌ | ✅异步 | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Ollama | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Grok(xAI) | ✅ | ✅ | ⚠️ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ |
| Mistral | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ |
| Cohere | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ⚠️ | ❌ | ❌ | ✅ |
| llama.cpp | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ |
| AWS Bedrock | ✅ | ✅ | ⚠️ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

### 2.1 矩阵要点

- **Rerank 全库仅 Qwen 一家实现**（`QwenRerankClient`），以 Rerank 立身的 Cohere 反而无接入。
- **Batches 仅 4 家**：OpenAI / Azure / Anthropic / 智谱。
- **Realtime 5 家**：OpenAI / Gemini / Qwen / Zhipu / Doubao。
- **微调 3 家**：OpenAI / Azure / 百度千帆；其余为"控制台手动"或无。
- **内容审核仅 OpenAI / Azure**。
- 所有 16 平台 chat 与 function calling 均完整。

### 2.2 占位能力清单（方法存在但抛异常）

| # | 平台 | 方法 | 证据文件:行 | 抛出内容 |
|---|---|---|---|---|
| 1 | Grok | `embed` | `sure-ai-grok/.../GrokClient.java:74-75` | `AiException("Grok (xAI) does not provide embeddings API")` |
| 2 | Bedrock | `embed` | `sure-ai-bedrock/.../BedrockClient.java:201-202` | `AiException("Bedrock embeddings (Titan) is not supported yet")` |
| 3 | DeepSeek | `embed` | `sure-ai-deepseek/.../DeepSeekClient.java:69-70` | **`UnsupportedOperationException`**（与全库 AiException 体系不一致） |
| 4 | 智谱 | `listModels` | `sure-ai-zhipu/.../ZhipuClient.java:91` | `AiException("Zhipu does not provide a public models list API")` |
| 5 | 豆包 | `listModels` | `sure-ai-doubao/.../DoubaoClient.java:76` | `AiException("Doubao models list requires Volcengine IAMS signature")` |
| 6 | Cohere | `listModels` | `sure-ai-cohere/.../CohereClient.java:137` | `AiException("Cohere v2 does not provide a models list API")` |
| 7 | 百度 | PDF/文档输入 | `sure-ai-baidu/.../BaiduClient.java:490` | `AiException("Baidu does not support document/PDF input")` |

> README 平台表与代码基本吻合，未发现"README 声称支持但代码抛异常"的硬伤。但 10 个 `extends OpenAiCompatClient` 的客户端对象上仍挂着上游并不存在的方法（如 MistralClient 继承了 `moderate()`/`generate(VideoRequest)`），直接调用会运行时 4xx。

---

## 三、各维度问题详述

### 3.1 产品维度（a2）

#### 竞品对比（事实均经网络搜索核实）

| 维度 | sureai (v1.2.0) | LangChain4j | Spring AI |
|---|---|---|---|
| 当前版本 | 1.3.0-SNAPSHOT（v1.2.0 已发布） | ~1.16.1-beta26 | 2.0.0 GA（2026-06-12） |
| LLM 提供商 | 16 个（国产全覆盖） | 20+ provider + 30+ 向量库 | 主流 6+，2.0 收敛为 SDK 单实现 |
| 第三方依赖 | **零**（仅 sure-core，自带 JSON/HTTP/SSE） | 传递依赖链庞大（Jackson/OkHttp 等） | 绑定 Spring 生态 |
| 调用范式 | **静态工具类**一行调用 | Builder 装配 / 依赖注入 | ChatClient fluent API + Bean 注入 |
| Agent 编排 | 自研 ReAct + Plan-and-Execute + 多 Agent 并行 | 原生 @Tool + ReAct，生态成熟 | ToolCallingAdvisor / Advisor 体系 |
| RAG | 内置分块/向量库/混合检索(BM25)/重排 | RetrievalAugmentor/ContentRetriever | QuestionAnswerAdvisor + VectorStore ETL |
| MCP | 自研 JSON-RPC 客户端（stdio+HTTP）+ 接入 ToolRegistry | 原生 MCP 支持 | MCP 一等公民，Client/Server Boot Starter |
| 结构化输出 | response_format + 自研 record 反序列化，8 平台 | POJO 结构化输出成熟 | BeanOutputConverter |
| 国产平台 | **强**（百度/智谱/豆包/通义/Moonshot 原生模块） | 部分（需额外模块） | 弱 |
| 企业特性 | 熔断/重试/指标/缓存/限流全自研零依赖 | 社区生态 | Micrometer/可观测/Spring 企业集成 |

> 来源：langchain4j GitHub README、Google Codelabs 2026-09、spring.io 官方博客、spring.io/projects/spring-ai、techoral 2026-05 横向对比。

**差异化结论**：sureai 真正的护城河是"零依赖 + 静态一行调用 + 国产 16 平台全覆盖"；短板是生态广度（rerank/审核/batch/realtime 覆盖面窄）、Spring 原生深度不足、文档/示例在长尾平台上不齐。

#### 易用性问题

- **BedrockUtil 静态入口范式断裂**：全文件只有 `create()`（`BedrockUtil.java:82`），没有 `init()/client()/chat()/chatStream()`，与其余 15 个平台"一行调用"承诺不一致。
- **能力入口命名分化**：OpenAi/Qwen/Zhipu/Azure 用 `image()/video()/tts()/stt()` 直出结果；Doubao 用 `videoClient()/ttsClient()/sttClient()` 拿 client 对象；Baidu 用 `imageClient()`；Gemini 只有 `image()`。
- **默认模型不统一**：仅 Bedrock 有 `SURE_AI_BEDROCK_MODEL` 默认模型环境变量，其余平台每次必传 model 名。
- **Maven Central 徽章版本硬编码错误**：`README.md:5` 与 `README.en.md:5` 徽章仍写 `maven--central-0.1.0`，工程已到 v1.2.0。

#### 文档与示例覆盖度

- docs/ 共 42 文件，16 平台全部有专属文档（`docs/platforms/` 下 11 个 + 顶层 5 个）。
- examples/ 共 29 个 Demo，**平台 Demo 仅 12/16**：缺 Cohere、Grok、llama.cpp、Mistral。
- 中英文 README 结构 1:1，未发现实质性内容错位。

---

### 3.2 架构维度（a3）

#### 模块依赖方向（实测）

```
sure-core (唯一底座)
  ├── 16 个平台模块（仅→core）
  ├── sure-ai-rag（→core）
  ├── sure-ai-agent（→core）
  ├── sure-ai-micrometer（→core，micrometer-core provided）
  └── sure-ai-mcp（→core + agent）
sure-ai-all = core + rag + agent + mcp + 16 平台（聚合）
sure-ai-spring-boot-starter → all(pom) + spring-boot-autoconfigure
sure-ai-examples → all(pom) + agent（agent 冗余，all 已含）
sure-ai-benchmark → core only（jmh test scope）
```

- **无循环依赖**：core 不依赖任何内部模块；平台模块未直接依赖 agent/rag/mcp。
- **sure-ai-all 聚合洁净**：未包含 micrometer/spring-boot-starter/benchmark/examples；唯一第三方运行期依赖为 sure-core:0.2.0（Apache-2.0 兼容）。

#### 核心架构问题

**【P0-A1】配置重建静默丢弃跨切面字段**

10 个 OpenAI 兼容平台子类构造器在"补默认 baseUrl"时重建 AiConfig，但只拷贝 8 个基础字段（apiKey/baseUrl/timeout/connectTimeout/proxy/organization/maxRetries/extraHeaders），**丢弃了 metricsCollector/retryListeners/rateLimitQps/cacheStore/cacheTtl/circuitBreaker**。

- 证据：`OpenAiClient.java:67-77`（`applyDefaultBaseUrl` 方法），同样模式出现在 DeepSeekClient:84-93、GrokClient:89-98、MistralClient:80-89、LlamaCppClient:82-91、QwenClient、AzureClient、DoubaoClient、MoonshotClient、ZhipuClient、BaiduClient 共 10+ 处。
- 影响：用户调用 `new OpenAiClient(AiConfig.builder().apiKey(k).cacheStore(lru).circuitBreaker(cb).build())` 时，cache 和 circuitBreaker 在构造器重建后**静默丢失**，无任何警告。用户以为配了缓存/熔断，实际没生效。
- 根因：`applyDefaultBaseUrl` 方法在 10 个子类中逐字复制（~17 行×10），未上提到基类；且 AiConfig 缺少 `withBaseUrl(String)` 保留全字段的便捷方法。

**【P0-A2】BedrockClient 完全绕过 AbstractAiClient**

`BedrockClient implements AiClient`（`BedrockClient.java:74`），不继承 AbstractAiClient，自建 HttpClient（`:140`），手写 HTTP 发送（`:160`），手写 SSE 读取（`:187`），手写错误映射（`:344`）。

- 证据：`chat()` 方法体（`:151-170`）无任何重试循环；无 `RateLimiter`/`CircuitBreaker`/`MetricsCollector`/`CacheStore`/proxy 支持；超时硬编码 `CONNECT_TIMEOUT=10s`、`REQUEST_TIMEOUT=120s`（`:80-83`），不接受 AiConfig 配置；凭证不走 AiConfig，构造器直接收 `(accessKey, secretKey, sessionToken, region, modelId)`（`:112`）。
- 影响：Bedrock 用户配置的缓存/指标/限流/熔断/代理全部静默失效；重试策略与其他 15 个平台不一致，生产环境可靠性落差大。
- 关联：BedrockUtil 静态入口也因此只有 `create()` 而无 `init()/client()/chat()`（见产品维度 P1-B2）。

**【P1-A3】Spring Boot Starter 漏配 5 个平台**

`SureAiProperties.java` 仅声明 11 个平台属性块（openai/azure/anthropic/gemini/deepseek/qwen/zhipu/moonshot/doubao/baidu/ollama），`SureAiAutoConfiguration.java` 仅 11 个 @Bean 方法。

- 缺失：grok、mistral、llamacpp、cohere、bedrock 共 5 个平台无自动装配。
- 影响：这 5 个平台的 Spring 用户无法通过 `sure.ai.grok.api-key=...` 自动获得 Bean，必须手动 new。与 README 声称的"16 平台全覆盖"不一致。

**【P1-A4】Spring PlatformProperties 仅 6 字段，与 AiConfig 不对齐**

`PlatformProperties.java` 仅有 `enabled/apiKey/baseUrl/model/timeout/maxRetries` 6 字段。`buildConfig()`（`SureAiAutoConfiguration.java:67-79`）只映射 apiKey/baseUrl/timeout/maxRetries 四个字段。

- 缺失：AiConfig 的 `proxy/organization/extraHeaders/connectTimeout/rateLimitQps/cacheStore/cacheTtl/circuitBreaker/retryListeners/metricsCollector` 全部无法通过 Spring properties 配置。
- 影响：Spring 用户无法通过 yml 配置代理/限流/熔断/缓存，必须手动 @Bean 构造 AiConfig，starter 的便利性打折。

**【P1-A5】Baidu 双 Key 在 Spring 中无法注入 SECRET_KEY**

`PlatformProperties` 只有一个 `apiKey` 字段，但 Baidu 需要 API_KEY + SECRET_KEY。`SureAiAutoConfiguration.baiduClient()` 只传了 apiKey（`:207-209`），SECRET_KEY 无法通过 `sure.ai.baidu.*` 注入，只能走环境变量。

**【P1-A6】AbstractAiClient 上帝类（624 行，7 项职责）**

`AbstractAiClient.java` 624 行，同时承担：①HttpClient 构建与代理、②JSON POST/GET/二进制/multipart 发送、③重试退避、④限流令牌获取、⑤熔断包装、⑥指标埋点、⑦multipart body 拼装、⑧错误映射。

- 影响：职责过多，后续新增传输层（如异步 HTTP/2）需修改此类，违反 SRP。当前"开关式 null 即零开销"的设计控制了复杂度，可接受但应关注演化方向。

**【P1-A7】OpenAiCompatClient 膨胀（841 行，实现 8 接口）**

`OpenAiCompatClient.java:90-92` 声明实现 8 个接口（AiClient/EmbeddingClient/ImageClient/VideoClient/AudioClient/ModelsClient/ModerationClient/FineTuneClient），841 行。

- 平台特有逻辑泄漏到基类的证据：`injectGroundingTool()`（`:485-496`）硬编码 `"web_search"` 字符串（OpenAI/Doubao/Qwen 特定）；`videoPollIntervalMs=2000`/`videoMaxWaitMs=120000`（`:125-128`）写死在基类，但视频轮询并非所有 10 个子类都需要；`parseSttResponse` 中 segment/words 双层解析（`:381-421`）是 OpenAI whisper 特定格式。
- 影响：10 个平台继承此类，任何协议字段变更都要改基类，可能影响全部 10 个平台。

**【P1-A8】DefaultRealtimeConnector 每次 connect() 新建 HttpClient**

`DefaultRealtimeConnector.java:53` `HttpClient client = HttpClient.newHttpClient();` 在 `connect()` 方法体内，每次 WebSocket 建连都新建一个 HttpClient（含自建 Executor），方法返回后 client 引用丢失，无法关闭。

- 影响：频繁建立 Realtime 连接会累积 HttpClient 线程池（每个 HttpClient 默认有自己的 selector/executor 线程），造成线程泄漏。应将 HttpClient 提升为字段复用。

**【P1-A9】AgentOrchestrator 内部线程池无关闭契约**

`AgentOrchestrator.java:224` 未注入 executor 时 `Executors.newFixedThreadPool(4)`，但 AgentOrchestrator 类**未实现 AutoCloseable**，无 `close()`/`shutdown()` 方法。Javadoc 说"调用方负责 shutdown"，但编译期无强制。

- 影响：用户忘记手动 shutdown 时，4 个线程常驻（非 daemon），阻止 JVM 退出。

---

### 3.3 代码质量与测试回归维度（a5）

#### 安全面（重点发现）

**【P0-Q1】Milvus filter 注入可致全表数据丢失**

`MilvusVectorStore.delete(id)`（`:146`）将 `id` 未转义直接拼进 Milvus 过滤表达式：
```java
body.set("filter", "id in [\"" + id + "\"]");
```
- 证据：`sure-ai-rag/src/main/java/com/sure/ai/rag/store/MilvusVectorStore.java:143-149`
- 影响：若 `id` = `x" or "1"="1`，filter 变为 `id in ["x" or "1"="1"]`，匹配全表 → **误删全集合数据**。id 来自不可信输入时为 P0 级数据丢失。
- 同类检查：ChromaVectorStore 使用 JSON body 传 filter（非字符串拼接），无此问题。

**【P1-Q2】HttpTool SSRF 无内网/元数据地址防护 + 响应体 OOM**

`HttpTool.execute`（`:98-154`）：
- ✅ scheme 校验确实实现了（`:110-114` 显式判断 http/https）。
- ❌ **无内网地址/云元数据保护**：仅校验 scheme，不校验 host。`http://169.254.169.254/latest/meta-data/`、`http://127.0.0.1:xxxx/admin`、`http://10.x/` 全部可达。在 LLM agent 场景下，prompt injection 可诱导模型调用该工具打内网。无 DNS rebinding 防护。
- ❌ **响应体内存放大**：`:141-146` 先用 `BodyHandlers.ofString()` 把整个响应读入内存，之后才截断到 `maxResponseLength=8000`。恶意/故障服务器发 2GB 响应会在截断前 OOM。
- 证据：`sure-ai-agent/src/main/java/com/sure/ai/agent/tool/builtin/HttpTool.java:110-114, 141-146`
- 评级：P1（若该工具被注册给不可信模型输出，则升级 P0）。

**【P1-Q3】百度 client_secret / access_token 拼在 URL 查询串**

`BaiduClient.java:541-542` OAuth 换 token 时把 `client_secret=` 拼在 URL 查询串：
```java
String url = ... + "/oauth/2.0/token?grant_type=client_credentials"
    + "&client_id=" + this.config.apiKey() + "&client_secret=" + this.secretKey;
```
同文件 `:174/:188/:201` 把 `access_token` 也拼在 URL 查询串上。

- 影响：查询串会进代理访问日志、服务器 access log。凭证明文进 URL，应改请求头/请求体。
- 证据：`sure-ai-baidu/src/main/java/com/sure/ai/baidu/BaiduClient.java:541-542, 174, 188, 201`

**【P1-Q4】MCP stdio 无 NDJSON 帧大小上限，可致 OOM**

`LineFrameMcpTransport.readLoop`（`:118-121`）：
```java
BufferedReader br = new BufferedReader(new InputStreamReader(this.in, UTF_8));
while ((line = br.readLine()) != null) { ... dispatch(line); }
```
- `readLine()` 一次性把整行读入 String，**没有最大行长限制**。恶意 MCP server 持续写字节不发 `\n`（或写 1GB 单行）→ BufferedReader 内部缓冲无限增长 → OOM。
- 证据：`sure-ai-mcp/src/main/java/com/sure/ai/mcp/transport/LineFrameMcpTransport.java:118-121`

**【P1-Q5】StdioMcpTransport.doClose 无超时 destroyForcibly 兜底**

`StdioMcpTransport.doClose()`（`:83-92`）用 `process.destroy()`（温和终止）+ `waitFor()` **无超时**。子进程忽略 SIGTERM 时 `close()` 永久阻塞。无 `destroyForcibly` 兜底。

- 证据：`sure-ai-mcp/src/main/java/com/sure/ai/mcp/transport/StdioMcpTransport.java:83-92`

#### 测试回归

**【P1-Q6】MCP 超时/异常断开/超大帧零测试**

`LineFrameMcpTransportTest` 只有 happy-path + malformed line 两个用例：
- `sendRequest` 的 TimeoutException 分支（`:90-92`）未被覆盖（无"server 永不回复→30s 超时"用例）。
- 读循环 `IOException`/`finally failPending` 分支（`:127-134`）无测试（server 异常断开场景）。
- 超大帧/无换行 OOM 无测试（与 P1-Q4 代码缺口对应）。
- 证据：`sure-ai-mcp/src/test/java/com/sure/ai/mcp/transport/LineFrameMcpTransportTest.java`

**【P1-Q7】CI 覆盖率上传缺 9 个模块**

`.github/workflows/ci.yml:37-50` artifact upload 仅列 12 个模块（core/openai/azure/anthropic/gemini/deepseek/qwen/zhipu/moonshot/doubao/baidu/ollama），但实际有 jacoco 报告的模块是 21 个。

- 缺失上传：`sure-ai-agent`、`sure-ai-mcp`、`sure-ai-micrometer`、`sure-ai-rag`、`sure-ai-bedrock`、`sure-ai-cohere`、`sure-ai-grok`、`sure-ai-llamacpp`、`sure-ai-mistral`。
- 影响：agent/mcp/rag/bedrock 等核心新模块的覆盖率在 CI  artifact 中失明，无法做覆盖率趋势追踪。
- 另：覆盖率只 upload artifact，无 codecov 上报、无 PR 覆盖率门禁评论。

**【P1-Q8】3 个零断言凑数测试**

`DoubaoSttClientTest.testUtilReset` / `DoubaoTtsClientTest.testUtilReset` / `DoubaoVideoClientTest.testUtilReset`：方法体只有 `DoubaoUtil.resetSttClient();` **零断言**，仅为覆盖 reset 方法行而存在。

- 证据：`sure-ai-doubao/src/test/java/com/sure/ai/doubao/` 下对应 Test 类
- 这是"只调用不验证"的凑数测试，拉低覆盖率含金量。

#### 代码质量

**重复代码实证**：
- `applyDefaultBaseUrl` 在 10 个 OpenAI 兼容子类逐字复制（~17 行×10，与 P0-A1 同源）。
- 各平台 Util 的 DCL 单例样板（client/lock/init/buildConfigFromEnv/reset）在 ~20 类复制，保守估计 600-900 行纯样板。以 QwenUtil 为例，为 chat/image/video/rerank/realtime 5 个子客户端各复制一遍 DCL 样板（`QwenUtil.java:62-435`）。
- Milvus/Chroma 的 `trimSlash`（Milvus:310-315 vs Chroma:318-323 逐字相同）、`toList(float[])`（Milvus:232-238 vs Chroma:254-260 逐字相同）、HTTP send 样板重复，~35 行×2，占两文件合计 913 行的 ~8%。

**并发安全**：DCL 单例（AgentUtil/QwenUtil/OpenAiUtil/BaiduClient）volatile+局部变量+独立锁，实现规范；ToolRegistry（ConcurrentHashMap）、LruCacheStore（synchronized）、InMemoryConversationMemory（synchronized）、CircuitBreaker（ReentrantLock）均正确。未发现共享可变状态未同步的情况。

**flaky 风险**：全部测试用端口 0 临时端口，无固定端口冲突；`CircuitBreakerIntegrationTest:141`（300ms sleep）和 `LruCacheStoreTest`（TTL sleep）是时序型测试，在 contended CI runner 上有 flaky 概率。

**CI 矩阵**：JDK [21, 25] 双版本 ✅；OS 仅 ubuntu-latest，无 Windows/macOS ❌；无发布/部署 workflow ❌；benchmark 无 CI 自动执行/基线对比 ❌。

---

## 四、P0/P1/P2 问题汇总表

### P0（必须优先整改，共 3 项）

| ID | 问题 | 维度 | 证据 | 影响 |
|---|---|---|---|---|
| P0-1 | 配置重建静默丢弃 cache/metrics/ratelimit/circuitBreaker/retryListeners | 架构 | `OpenAiClient.java:67-77` 等 10+ 处 `applyDefaultBaseUrl` | 用户配置的缓存/熔断/限流/指标静默失效，无警告 |
| P0-2 | BedrockClient 完全绕过 AbstractAiClient | 架构 | `BedrockClient.java:74,140,151-170` | Bedrock 无重试/限流/熔断/指标/缓存/代理，生产可靠性落差 |
| P0-3 | Milvus filter 注入可致全表数据丢失 | 安全 | `MilvusVectorStore.java:146` `id in ["` + id + `"]` | 恶意 id 可匹配全表，误删整个集合向量 |

### P1（近期版本整改，共 14 项）

| ID | 问题 | 维度 | 证据 | 影响 |
|---|---|---|---|---|
| P1-1 | HttpTool SSRF 无内网/元数据防护 + 响应体全量入内存 OOM | 安全 | `HttpTool.java:110-114, 141-146` | LLM agent 场景下 prompt injection 可打内网；恶意服务器可致 OOM |
| P1-2 | 百度 client_secret/access_token 拼在 URL 查询串 | 安全 | `BaiduClient.java:541-542, 174, 188, 201` | 凭证明文进代理/服务器访问日志 |
| P1-3 | MCP stdio 无 NDJSON 帧大小上限，可致 OOM | 安全 | `LineFrameMcpTransport.java:118-121` | 恶意 MCP server 不发换行可致 OOM |
| P1-4 | StdioMcpTransport.doClose 无超时 destroyForcibly 兜底 | 资源 | `StdioMcpTransport.java:83-92` | 子进程 hang 时 close() 永久阻塞 |
| P1-5 | MCP 超时/异常断开/超大帧零测试 | 测试 | `LineFrameMcpTransportTest.java` 仅 2 用例 | 传输层核心可靠性路径无覆盖 |
| P1-6 | CI 覆盖率上传缺 9 个模块（agent/mcp/rag/bedrock 等） | CI | `ci.yml:37-50` 仅列 12 模块 | 核心新模块覆盖率在 CI 失明 |
| P1-7 | 3 个零断言凑数测试（doubao resetStt/Tts/Video） | 测试质量 | `sure-ai-doubao/src/test/.../*Test.java` | 拉低覆盖率含金量 |
| P1-8 | Spring Boot Starter 漏配 5 个平台（grok/mistral/llamacpp/cohere/bedrock） | 架构 | `SureAiProperties.java` / `SureAiAutoConfiguration.java` | 5 平台 Spring 用户无法自动装配 Bean |
| P1-9 | Spring PlatformProperties 仅 6 字段，无法配置 proxy/限流/熔断/缓存 | 架构 | `PlatformProperties.java` / `SureAiAutoConfiguration.java:67-79` | Spring 用户 starter 便利性打折 |
| P1-10 | Baidu 双 Key 在 Spring 中无法注入 SECRET_KEY | 架构 | `SureAiAutoConfiguration.java:207-209` | Baidu Spring 用户只能走环境变量配 secret |
| P1-11 | BedrockUtil 静态入口范式断裂（无 init/client/chat） | 产品 | `BedrockUtil.java:82` 只有 create() | Bedrock 用户无法一行调用，与 15 平台不一致 |
| P1-12 | Rerank 仅 Qwen 一家，Cohere（Rerank 鼻祖）缺失 | 产品 | 全库仅 `QwenRerankClient` | 与"RAG 二阶段精排"卖点不匹配 |
| P1-13 | 4 个平台缺 Demo（Cohere/Grok/llama.cpp/Mistral） | 产品 | `sure-ai-examples/` 仅 12/16 平台 Demo | 新用户无法照抄上手 |
| P1-14 | DeepSeek embed 抛 UnsupportedOperationException（与全库 AiException 不一致） | 产品/异常 | `DeepSeekClient.java:69-70` | 异常体系统一性自破 |

### P2（排期跟进，共 12 项）

| ID | 问题 | 维度 | 证据 |
|---|---|---|---|
| P2-1 | Maven Central 徽章版本硬编码 0.1.0（应为 1.2.0） | 产品 | `README.md:5` / `README.en.md:5` |
| P2-2 | 各平台 Util DCL 单例样板重复 600-900 行 | 代码质量 | `QwenUtil.java:62-435` 等 ~20 类 |
| P2-3 | Milvus/Chroma trimSlash/toList/HTTP send 样板重复 ~8% | 代码质量 | `MilvusVectorStore.java:310-315` vs `ChromaVectorStore.java:318-323` |
| P2-4 | 路径参数 taskId/jobId/batchId 未 URL encode | 代码质量 | `OpenAiCompatClient.java:264, 803` |
| P2-5 | Baidu token 刷新注释称"持锁 sleep"实为持锁阻塞 HTTP IO | 代码质量 | `BaiduClient.java:527-530` vs exclude-filter.xml:56-61 |
| P2-6 | Client 能力面未按平台收口（继承基类后挂着上游不存在的方法） | 架构 | Mistral/Moonshot 等继承 OpenAiCompatClient 后 moderate()/generate() 运行时 4xx |
| P2-7 | 默认模型不统一（仅 Bedrock 有 SURE_AI_BEDROCK_MODEL） | 产品 | 各 *Util 无默认模型 |
| P2-8 | 能力入口命名分化（image() 直出 vs xxxClient() 取对象） | 产品 | Doubao/Baidu/Gemini vs OpenAi/Qwen |
| P2-9 | AbstractAiClient 624 行承担 7 项职责（上帝类） | 架构 | `AbstractAiClient.java` 全文 |
| P2-10 | OpenAiCompatClient 841 行实现 8 接口，平台特有逻辑泄漏基类 | 架构 | `OpenAiCompatClient.java:485-496` (web_search), `:125-128` (video polling) |
| P2-11 | DefaultRealtimeConnector 每次 connect() 新建 HttpClient，线程泄漏 | 资源 | `DefaultRealtimeConnector.java:53` |
| P2-12 | AgentOrchestrator 内部 newFixedThreadPool(4) 无 AutoCloseable 契约 | 资源 | `AgentOrchestrator.java:224`，类未实现 AutoCloseable |
| P2-13 | examples 显式依赖 sure-ai-agent 冗余（all 已含） | 架构 | `sure-ai-examples/pom.xml` |
| P2-14 | BedrockClient IOException 包成通用 AiException 而非 AiTimeoutException | 异常 | `BedrockClient.java:165` |
| P2-15 | Anthropic/Baidu/Ollama 流式路径绕过基类，无重试/熔断，应 JavaDoc 标注 | 架构 | `AnthropicClient.java:404` / `BaiduClient.java:252` / `OllamaClient.java:222` |
| P2-16 | SSE [DONE] 标记、真 chunked 分块、malformed JSON/空响应无测试 | 测试 | `SseLineReaderTest` / `BedrockClientTest` |
| P2-17 | 无时序无关测试分类（@Category/profile），慢 E2E 与单测同跑 | 测试 | 全测试树无 Tag/Category |
| P2-18 | CI 仅 ubuntu，无 win/mac 矩阵；无发布 workflow；无 codecov | CI | `ci.yml` 全文 |
| P2-19 | benchmark 无 CI 自动执行/基线对比，需手动 exec:java | CI | `sure-ai-benchmark/pom.xml` exec phase=none |
| P2-20 | CircuitBreakerIntegrationTest(300ms)/LruCacheStoreTest 时序型测试有 flaky 概率 | 测试 | `CircuitBreakerIntegrationTest.java:141` |
| P2-21 | 新增平台需改 5 处 pom/bom/all/README/spring-starter 样板，README 22 列宽表维护成本高 | 可维护性 | 新增平台改动面实测 |
| P2-22 | 个别静态工厂方法缺 @return JavaDoc（McpResponse.fromJson 等） | 代码质量 | `sure-ai-mcp/.../message/McpResponse.java` |

---

## 五、整改方向与具体任务清单

> 每条含：问题描述 + 证据 + 影响 + 整改任务 + 建议归属版本 + 粗估工作量（a4 研发工程师评估）

### P0 整改任务

#### T-P0-1：AiConfig 增加 withBaseUrl 保留全字段方法，消除 10 处配置重建丢字段
- **问题**：10 个 OpenAI 兼容平台 `applyDefaultBaseUrl` 重建 AiConfig 时丢弃 cache/metrics/ratelimit/circuitBreaker/retryListeners
- **证据**：`OpenAiClient.java:67-77` 等 10+ 处
- **影响**：用户跨切面配置静默失效
- **整改任务**：
  1. 在 `AiConfig` 增加 `withBaseUrl(String baseUrl)` 方法（返回新实例，保留全部字段）
  2. 将 10 个子类的 `applyDefaultBaseUrl` 替换为 `config.withBaseUrl(defaultBase)`
  3. 删除 10 处重复的 `applyDefaultBaseUrl` 方法
  4. 增加单元测试验证 withBaseUrl 保留全部字段（含 cacheStore/circuitBreaker/metricsCollector）
- **建议版本**：1.2.1（热修复）
- **粗估工作量**：1.5 人天

#### T-P0-2：BedrockClient 接入 AbstractAiClient 跨切面体系
- **问题**：BedrockClient 完全绕过 AbstractAiClient，无重试/限流/熔断/指标/缓存/代理
- **证据**：`BedrockClient.java:74, 140, 151-170`
- **影响**：Bedrock 生产可靠性与其他 15 平台不一致
- **整改任务**：
  1. 让 BedrockClient 继承 AbstractAiClient（或组合使用其 executeWithRetry 模板）
  2. SigV4 签名通过 applyAuth 钩子或请求拦截器注入（不破坏基类 HTTP 发送路径）
  3. 超时/代理从 AiConfig 读取，移除硬编码
  4. 凭证纳入 AiConfig.extraHeaders 或扩展 AiConfig 字段
  5. 流式 SSE 路径复用基类 doPostStream 模板（或标注此路径不享有重试）
  6. BedrockUtil 补齐 init()/client()/chat()/chatStream() 静态入口（关联 T-P1-11）
- **建议版本**：1.3.0（架构调整，需充分测试）
- **粗估工作量**：4-5 人天

#### T-P0-3：修复 Milvus filter 注入
- **问题**：`delete(id)` 将 id 未转义拼进 filter 表达式，可致全表删除
- **证据**：`MilvusVectorStore.java:146`
- **影响**：数据丢失（id 不可信时 P0）
- **整改任务**：
  1. 对 id 做转义（过滤 `"` 和 `\`，或使用参数化 filter 语法）
  2. 增加单元测试：含特殊字符的 id 不导致 filter 注入
  3. 检查 MilvusVectorStore 其他 filter 拼接点（如 similaritySearch 的 filter 参数）
- **建议版本**：1.2.1（热修复）
- **粗估工作量**：0.5 人天

### P1 整改任务（精选高优先级，完整清单见汇总表）

#### T-P1-1：HttpTool 加内网 IP 黑名单 + 流式限长读取
- **整改任务**：①增加私有/回环/链路本地段 IP 拒绝清单（127.0.0.0/8、10.0.0.0/8、172.16.0.0/12、192.168.0.0/16、169.254.0.0/16、fc00::/7）；②解析后校验 IP（防 DNS rebinding）；③用 `BodyHandlers.ofInputStream()` 流式限长读取（最多读 maxResponseLength+1 字节即截断）
- **建议版本**：1.2.1
- **粗估工作量**：2 人天

#### T-P1-2：百度凭证改请求头/请求体，移出 URL 查询串
- **整改任务**：OAuth token 请求改 POST body（`grant_type/client_id/client_secret`）；API 调用 `access_token` 改 `Authorization: Bearer` 头
- **建议版本**：1.3.0
- **粗估工作量**：1 人天

#### T-P1-3：MCP NDJSON 帧长上限 + close 强制 destroy 兜底
- **整改任务**：①`LineFrameMcpTransport` 用自定义 `BoundedReader` 或 `readLine` 包装最大行长（如 64MB），超限抛异常并关闭传输；②`StdioMcpTransport.doClose` 先 `destroy()`，`waitFor(5, SECONDS)` 超时后 `destroyForcibly()`
- **建议版本**：1.2.1
- **粗估工作量**：1 人天

#### T-P1-4：MCP 传输层异常路径补测试
- **整改任务**：增加 ①server 永不回复→超时断言；②server 异常断开→pending future 全部 fail 断言；③超大帧→帧长限制触发断言
- **建议版本**：1.2.1
- **粗估工作量**：1 人天

#### T-P1-5：CI 覆盖率上传补全 21 模块 + codecov 上报
- **整改任务**：①`ci.yml` artifact path 改为 `sure-ai-*/target/site/jacoco` 通配（或补全缺失的 9 模块）；②增加 codecov action 上报；③可选：增加 PR 覆盖率评论
- **建议版本**：1.3.0
- **粗估工作量**：0.5 人天

#### T-P1-6：Spring Boot Starter 补配 5 平台 + 扩展 PlatformProperties
- **整改任务**：①SureAiProperties 增加 grok/mistral/llamacpp/cohere/bedrock 嵌套属性；②SureAiAutoConfiguration 增加对应 5 个 @Bean；③PlatformProperties 增加 proxy/connectTimeout/rateLimitQps/circuitBreaker/cacheStore 字段（或文档说明高级配置需手动 @Bean）；④Baidu 增加 secretKey 字段
- **建议版本**：1.3.0
- **粗估工作量**：3 人天

#### T-P1-7：补 4 个平台 Demo + 修复零断言测试
- **整改任务**：①新增 CohereDemo/GrokDemo/LlamaCppDemo/MistralDemo；②Doubao 3 个 reset 测试增加断言（断言 reset 后 client 为 null 或重新初始化）
- **建议版本**：1.3.0
- **粗估工作量**：1.5 人天

#### T-P1-8：Cohere Rerank 接入 + DeepSeek 异常类型统一
- **整改任务**：①新增 CohereRerankClient（Cohere v2 /rerank 端点）；②DeepSeek embed 占位改抛 AiException（与全库一致）
- **建议版本**：1.3.0
- **粗估工作量**：2 人天

### P2 整改任务（排期跟进，精选）

| 任务 | 内容 | 建议版本 | 工作量 |
|---|---|---|---|
| T-P2-1 | 修复 README 徽章版本 0.1.0→1.2.0 | 1.2.1 | 0.1 人天 |
| T-P2-2 | 抽 SingletonSupport 泛型基类消除 Util DCL 样板重复 | 1.4.0 | 3 人天 |
| T-P2-3 | DefaultRealtimeConnector HttpClient 提升为字段复用 | 1.3.0 | 0.5 人天 |
| T-P2-4 | AgentOrchestrator 实现 AutoCloseable + shutdown 钩子 | 1.3.0 | 0.5 人天 |
| T-P2-5 | 路径参数 URL encode（taskId/jobId/batchId） | 1.3.0 | 0.5 人天 |
| T-P2-6 | SSE [DONE]/chunked/malformed JSON 补测试 | 1.3.0 | 1 人天 |
| T-P2-7 | 增加测试分类（@Category unit/integration），E2E 可独立执行 | 1.4.0 | 1 人天 |
| T-P2-8 | CI 增加 Windows/macOS 矩阵 + 发布 workflow | 1.4.0 | 1 人天 |
| T-P2-9 | benchmark CI 自动执行 + 基线对比机制 | 1.4.0 | 1.5 人天 |
| T-P2-10 | Client 能力面收口（子类对不支持方法覆写 AiException 占位或提供 supports() 探测） | 1.4.0 | 2 人天 |

---

## 六、整改优先级路线建议

### 6.1 v1.2.1 热修复（建议 1 周内，~6 人天）

聚焦 P0 安全与正确性问题，不做架构调整：
1. **T-P0-1** AiConfig.withBaseUrl 消除配置丢字段（1.5 人天）
2. **T-P0-3** Milvus filter 注入修复（0.5 人天）
3. **T-P1-1** HttpTool 内网黑名单 + 流式限长（2 人天）
4. **T-P1-3** MCP 帧长上限 + close 强制 destroy（1 人天）
5. **T-P1-4** MCP 异常路径补测试（1 人天）
6. **T-P2-1** README 徽章版本修复（0.1 人天）

### 6.2 v1.3.0 架构整改（建议 2-3 周，~15 人天）

聚焦架构债与 Spring 生态补齐：
1. **T-P0-2** BedrockClient 接入 AbstractAiClient（4-5 人天）— 最大架构项
2. **T-P1-6** Spring Boot Starter 补 5 平台 + 扩展字段（3 人天）
3. **T-P1-7** 补 4 平台 Demo + 修复零断言测试（1.5 人天）
4. **T-P1-2** 百度凭证移出 URL（1 人天）
5. **T-P1-5** CI 覆盖率补全 + codecov（0.5 人天）
6. **T-P2-3** DefaultRealtimeConnector HttpClient 复用（0.5 人天）
7. **T-P2-4** AgentOrchestrator AutoCloseable（0.5 人天）
8. **T-P2-5** 路径参数 URL encode（0.5 人天）
9. **T-P2-6** SSE 边界补测试（1 人天）
10. **T-P1-8** Cohere Rerank + DeepSeek 异常统一（2 人天）

### 6.3 v1.4.0 可维护性与生态（建议排期跟进，~10 人天）

1. **T-P2-2** SingletonSupport 消除 Util DCL 样板（3 人天）
2. **T-P2-10** Client 能力面收口（2 人天）
3. **T-P2-7** 测试分类 unit/integration（1 人天）
4. **T-P2-8** CI 多 OS + 发布 workflow（1 人天）
5. **T-P2-9** benchmark CI 自动化 + 基线（1.5 人天）
6. P2 其余项（默认模型统一、能力入口命名统一、AbstractAiClient/OpenAiCompatClient 拆分等）按需排期

---

## 七、做得好的地方（如实记录）

1. **门禁纪律**：checkstyle/spotbugs/license 三门禁实测零违规，spotbugs 排除清单 21 条每条都有逐行设计理由，无掩盖真 bug。
2. **零依赖承诺**：全库唯一第三方运行期依赖为 sure-core:0.2.0（Apache-2.0 兼容），sure-ai-all 聚合洁净，无 Spring/Jackson/OkHttp 泄漏。
3. **并发安全**：DCL 单例（AgentUtil/QwenUtil/OpenAiUtil/BaiduClient）volatile+局部变量+独立锁实现规范；ToolRegistry/LruCacheStore/InMemoryConversationMemory/CircuitBreaker 并发容器选型正确。
4. **测试基础设施**：全部测试用端口 0 临时端口，无固定端口冲突；零真实网络调用（全部 HttpServer mock）；Bedrock/micrometer 测试断言质量高（既验响应映射又验请求头/请求体/路径）。
5. **模块依赖方向健康**：16 平台严格只依赖 core，无循环依赖，无越层依赖。
6. **SPI 扩展点开放**：VectorStore（7 方法）、McpTransport（4 方法）、RerankClient（1 方法）接口简洁，新增实现无需改 core。
7. **文档覆盖**：42 个 docs 文件，16 平台全部有专属文档，中英文 README 结构 1:1。
8. **新模块质量**：bedrock（89.5% 覆盖率，SigV4 官方向量校验）和 mcp（79.7% 覆盖率，E2E 子进程回环）的测试质量高于老模块平均水平。

---

*报告生成：sureai 软件研发小组（a2 产品 / a3 架构 / a5 质检 / a1 统筹），2026-09-25*
