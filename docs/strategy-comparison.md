# 竞品对标与超越路线（2026-10 版）

> 本文件是 sureai 对标 **Spring AI 2.0** 与 **LangChain4j** 的战略分析，以及「全面超越」的迭代路线。
> 事实截至 2026-10，均来自官方文档/发布公告（文末附依据）。本文件由软件研发团队产品/架构角色产出，供决策。

## 一、三者在 2026 的生态位（事实核对）

### Spring AI 2.0（2026-06-12 GA）
- **基线**：Spring Boot 4 / Spring Framework 7、Jackson 3、JSpecify 空安全注解
- **核心 Chat 模型仅 7 家**（SDK 直连）：OpenAI、Anthropic、Amazon Bedrock、Google GenAI、Mistral、DeepSeek、Ollama；OpenAI-proxy 协议可接 MiniMax / Moonshot / Groq / NVIDIA / Perplexity / 千帆
- **国产覆盖**：DeepSeek、MiniMax、Moonshot（proxy）；**智谱已在 2.0 移除**；通义需另引社区项目 Spring AI Alibaba；豆包/文心/混元/星火/阶跃/百川/01.AI/硅基流动**无官方支持**
- **编排**：ChatClient + Advisor 链（一阶工具循环、ToolSearch 渐进披露、StructuredOutputValidationAdvisor 自纠、Redis 语义缓存）
- **MCP**：官方 Java SDK（2025-11-25 规范）、mcp-annotations 注解暴露、Streamable HTTP 默认
- **向量库 14+**、SQL-like filter、Micrometer 可观测、评估 utilities
- **依赖模型**：深度绑定 Spring Boot 4 + 各厂商官方 SDK（重依赖）

### LangChain4j（2026）
- **15–20+ LLM 提供商、20+ 向量库**、Quarkus 官方扩展
- **国产覆盖**：阿里百炼 DashScope（通义）、百度文心、豆包兼容接口、Ollama、智谱等
- **编排**：AiServices 声明式接口（@SystemMessage/@Tool）、AgenticServices.agentBuilder()、Easy RAG
- **数据导入**：TXT / PDF / DOC / PPT / XLS / URL / GitHub / S3
- **依赖模型**：模块化，但各集成模块携带各自 SDK/客户端依赖

### sureai（2026-10，v2.3.0）
- **23 平台独立模块**：OpenAI / Azure / Anthropic / Gemini / DeepSeek / Qwen / Zhipu / Moonshot / Doubao / Baidu / Ollama / Grok / Mistral / Cohere / LlamaCpp / Bedrock / MiniMax / StepFun / Baichuan / 01.AI / SiliconFlow / Hunyuan / Spark
  - **国产全覆盖**：智谱（Spring AI 已移除）、豆包、文心、混元、星火、阶跃、百川、01.AI、硅基流动——**两大框架均无同等覆盖**
- **零运行期第三方依赖**（仅 `io.github.tasure:sure-core:0.2.0`；无 vendor SDK、无 Spring 绑定）
- **静态入口一行调用**：`DeepSeekUtil.chat(...)`，平台模块互相隔离
- **统一网关**：`sure-ai-gateway`（6 路由策略 / 故障转移 / 密钥池 / 租户限流 / 成本预算）——**LiteLLM 定位，两大框架均无对位能力**
- **RAG 深化**：HyDE / Multi-Query(RRF) / CRAG / 父子 / 语义分块 / GraphRAG / 多模态 / RAGAS 四指标评估；**9 向量库**
- **Agent 生产级**：ReAct / PlanExecute / Orchestrator + 检查点 / HITL / 流式事件 / 长期记忆 / 并行工具调用
- **MCP 双向**：客户端 + 服务端（stdio/HTTP，有状态 + 2026-07-28 无状态、MRTR/subscriptions）
- **信任链**：GPG 签名 + SBOM(CycloneDX) + SLSA provenance + 依赖扫描 + SECURITY.md；CI 全自动发布
- **开发者体验**：CLI（GraalVM native 实测 41MB / 2m58s）、Maven archetype、双语文档（docs/en 11 篇）、Quarkus/Spring 双 Starter
- **性能**：JSON 解析延迟 ↓32.8%（JMH 双轮归档）；1299 测试 / 42 模块全绿

## 二、优势 / 劣势矩阵

### 优势（sureai 独有或领先）
| 维度 | sureai | Spring AI 2.0 | LangChain4j | 结论 |
|---|---|---|---|---|
| 国产平台覆盖 | 23 平台全含（智谱/豆包/文心/混元/星火等） | 7 家核心，智谱移除，豆包/文心/混元/星火无 | 通义/文心/豆包兼容有，但依赖各自 SDK | **sureai 领先** |
| 运行期依赖 | 仅自家 sure-core，零第三方 | Spring Boot 4 + 厂商 SDK（OpenAI/Anthropic/Google SDK） | 各集成模块带 SDK/客户端依赖 | **sureai 领先（轻量）** |
| 引入门槛 | 一条 BOM 依赖 + 静态一行调用 | 需 Spring Boot 项目 + 配置类/Bean | 需选模块依赖 + AiServices 接口 | **sureai 最低** |
| 统一网关/成本 | Gateway 6 策略 + 成本目录 + 密钥池 | 无对位能力 | 无对位能力 | **sureai 独有** |
| 部署形态 | CLI/native/普通 jar 均可用 | 限 Spring 应用 | 限框架应用 | **sureai 领先** |
| 发布信任链 | SBOM+SLSA+GPG 全自动 | 官方维护 | 无同等级 | **sureai 领先** |
| 隔离性 | 每平台独立模块可只引所需 | 全家桶式 starter | 模块化 | 持平/领先 |

### 劣势（sureai 需追赶）
| 维度 | 差距 | 两大框架现状 |
|---|---|---|
| 声明式编排 | 无 AiServices 式「接口即服务」 | LangChain4j AiServices / AgenticServices |
| 中间件链 | 无 Advisor 链式抽象 | Spring AI Advisor（工具循环/自纠/语义缓存） |
| 语义缓存 | 仅有 KV 缓存（v1.1.0） | Spring AI 2.0 Redis Semantic Cache |
| 向量库数量 | 9 家 | Spring AI 14+ / LangChain4j 20+ |
| 文档导入 | 仅 TXT/MD/URL | LangChain4j PDF/DOC/PPT/XLS/S3/GitHub |
| 全链路评估 | 有 RAGAS 四指标 | Spring AI evaluation utilities、LangChain 生态 |
| 社区规模 | 起步期 | Spring 官方生态 / LangChain4j 成熟社区 |
| 生态扩展 | 无第三方生态 | vendor 维护模块、mcp-security 等 |

## 三、「全面超越」迭代路线（务实版）

> 定位不变：**Java 世界的 LiteLLM——零依赖、一行调用、国产全覆盖、生产可信**。
> 「超越」= ①易用门槛碾压 ②平台/国产覆盖碾压 ③生产可靠性追平并超越 ④补齐两大框架的编排/生态短板。

| 版本 | 主题 | 关键交付 | 对标补齐 |
|---|---|---|---|
| **v2.4.0**（进行中） | 实时对话与生态集成 | Realtime 重连/事件、RedisCacheStore、Langfuse exporter、平台能力核实 | 可观测追平 |
| **v2.5.0** | 声明式编排层 | `sure-ai-framework`：AiService 接口动态代理（@SystemMessage/@Tool/@Memory）、轻量 Advisor 链（工具循环/结构化输出自纠/日志）、SemanticCache（本地+Redis） | 追平 AiServices + Advisor |
| **v2.6.0** | 文档与向量库全覆盖 | 文档导入器（TXT/MD/PDF 纯 JDK 提取 + DOC/PPT/XLS 可选 provided 解析）、向量库追平 14+（Cassandra/MongoDB/Neo4j/PGVector/Typesense） | 追平 LangChain4j 数据导入 |
| **v2.7.0** | 网关生产化 | 语义缓存接入 Gateway、A/B 路由、动态模型切换、配额预算强化、Proxy 管理端点 | 拉开与两框架的网关差距 |
| **v2.8.0** | 评估与可观测终极 | Evals 全链路（LLM-as-judge 通用化）、Langfuse/OTel 完整、trace 回放与回归 | 追平评估/可观测 |
| **v2.9.0** | Agent 生态 | Agent Skills 规范实现、子 Agent 编排、工具注册中心市场、与 MCP 无状态深度融合 | 追平 AgenticServices |
| **v3.0.0** | 开发者体验终极 | 模板应用库（知识库/客服/代码助手一键生成）、CLI 增强、文档站、示例全中英 | 社区/体验里程碑 |

**交叉主线（每版都做）**：国产新平台持续接入、平台能力持续核实、性能基准持续归档、零依赖红线不破。

## 四、成功要素（与功能同等重要）

1. **5 分钟入门**：Quick Start 真正可跑 + archetype + CLI 三入口
2. **文档国际化**：中英双语文档持续扩充 + Cookbook 场景库
3. **社区运营**：good first issue、Discussions、月度发布、竞品对比页、快速响应 issue
4. **信任建设**：SBOM/SLSA/SECURITY.md/依赖扫描/Fuzz 持续加固
5. **诚实工程**：性能有证据、能力核实有依据、未达标如实披露

## 五、依据（2026-10 联网核实）

- Spring AI 2.0.0 GA 公告（spring.io/blog/2026/06/12）：核心 7 模型、advisor 链、MCP SDK、mcp-annotations
- Spring AI 2.0 参考文档（docs.spring.io/spring-ai/reference/2.0）：向量库 14+、SQL-like filter、Micrometer 观测
- Spring AI 2.0 模型列表（博客园 2026-05-29）：智谱移除、MiniMax/Moonshot/Groq 走 OpenAI-proxy
- LangChain4j 官方/中文文档（langchain4j.cn、github.com/langchain4j）：15+ 提供商、20+ 向量库、AiServices、文档导入
- LangChain4j Quarkus（JetBrains KotlinConf 2025）：Quarkus 扩展
- sureai 自身现状：v2.3.0 发布记录、docs/（capabilities/rag/agent/gateway/trust 等）
