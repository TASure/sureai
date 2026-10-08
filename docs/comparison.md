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

# 与同类框架的诚实对比

> 本文把 sureai 与业界四个常见的 JVM / Java 侧 AI 框架放在一起，只陈述各自**定位与事实**，帮助你按场景选型。我们不贬低任何竞品——它们在各自生态位上都是成熟、有社区背书的选择；sureai 只是在「零运行期第三方依赖 + 多平台模块隔离」这一条路径上做了不同取舍。

## 对比目的与边界

- **只比事实，不比高下**：定位、语言与生态、依赖形态、平台覆盖、特色能力、生态位。
- **数据来源**：竞品数据均来自其官方站点 / 官方文档，链接见文末「事实来源」；sureai 数据取自本仓库 [README](../README.md) 与 [docs/capabilities.md](capabilities.md)。
- **不臆测**：查不到公开权威信息的条目，一律标注「未核实」，不替竞品脑补。
- **立场**：sureai 是**多平台接入工具基础设施**（每个平台一个独立模块 + 静态入口工具类），不是一个全功能 Agent 编排框架；与下面多数竞品在抽象层上不在同一层。

## 对比维度表

| 维度 | sureai | Spring AI | langchain4j | Koog | Embabel |
|------|--------|-----------|-------------|------|---------|
| 定位 | 零依赖的多平台 LLM 接入工具库 | Spring 生态的 AI 应用框架 | Java 版 LangChain，LLM 集成工具包 | JetBrains 的 JVM 智能体框架 | Spring 创始人的 JVM 智能体流程框架 |
| 主语言 / 生态 | Java（JDK 21+） | Java，深度绑定 Spring | Java（兼 Kotlin） | Kotlin 为主，一等 Java 互操作 | Kotlin 为主，一等 Java 互操作 |
| 依赖形态 | **零运行期第三方依赖**（仅 JDK + 自研 JSON/HTTP），模块级隔离 | 依赖 Spring Boot / Spring Framework 全家桶 | 多模块，传递依赖链，常配 Spring Boot / Quarkus | Gradle 依赖（`ai.koog:koog-agents`），KMP 体系 | 基于 Spring Boot（据公开报道），Kotlin 编写 |
| 模型/平台覆盖 | **23 个平台**（国产平台全覆盖） | 主流商业 + 开源供应商（OpenAI/Azure/Anthropic/Google/Amazon/HuggingFace 等） | 主流供应商 + 大量向量库 | 多模型供应商，定位在 Agent 编排 | 多模型供应商，定位在 Agent 流程 |
| 运行目标平台 | JVM | JVM（Spring 应用） | JVM | **KMP 多端**：JVM / Android / iOS / JS / WasmJS | JVM |
| 特色能力 | 静态工具类一行调用、CLI、Gateway、RAG、Agent、MCP、成本计量、OpenAI 兼容代理 | POJO 抽象、Advisor RAG、与 Spring 生态无缝集成 | Chains、RAG、Agent、Spring/Quarkus/Helidon 集成 | 类型安全 Kotlin DSL、容错可扩展的生产级 Agent、跨端 | Actions/Goals/Conditions 流程建模、类型安全可测试、MCP Server 暴露、OTel 可观测 |
| 生态位 | 轻量接入层 / 基础设施 | 企业级 Spring 应用内 AI 能力 | 通用 Java LLM 应用开发 | 跨端 Agent 构建 | 企业级智能体业务流程编排 |

> 表中竞品的「平台覆盖」只描述其支持的供应商广度，**不逐一数个数**——各家供应商清单随版本快速变化，硬数易失真；sureai 的「23 平台」是本仓库当前模块数，可逐模块核对。

## 各竞品小节

### Spring AI

- **事实**：Spring AI 是「面向 AI 工程的应用框架」，把 Spring 生态的设计原则（可移植、模块化）带到 AI 领域，以 POJO 作为构建块；灵感来自 LangChain / LlamaIndex 但不是其直接移植。2.0 版本要求 Spring Boot 4.0/4.1 与 Spring Framework 7.0，底层升级到 Jackson 3；按供应商提供 `spring-ai-openai` 等模块与 starter。
- **生态位差异（sureai 视角）**：Spring AI 面向已经在用 Spring Boot 的团队，追求与 Spring 容器、配置体系无缝集成；它天然携带 Spring 全家桶依赖。sureai 不假设你用任何框架——纯 JDK、静态工具类，几行即可调通一个平台，适合不想被 Spring 绑定、或需要把 LLM 接入塞进已有任意 Java 进程的场景。

### langchain4j

- **事实**：仓库自述为「Java version of LangChain」，目标是简化 Java 应用接入 LLM：对各家 LLM 供应商与向量库提供统一 API，避免为每个服务单独学专有 API；提供与 Spring Boot / Quarkus / Helidon 的集成，Apache-2.0。
- **生态位差异（sureai 视角）**：langchain4j 是通用、功能面很全的 Java LLM 工具链（Chain / RAG / Agent 一整套），抽象丰富但传递依赖也随之而来。sureai 把自己收得很窄：只做「多平台接入 + 模块隔离 + 零依赖」，RAG / Agent 等能力是在零依赖核心之上按需叠加的独立模块。若你要的是一把「瑞士军刀」，langchain4j 更合手；若你要的是「一颗不增重的螺丝钉」，sureai 更合适。

### Koog

- **事实**：Koog 是 JetBrains 开源、面向 JVM 生态构建 AI Agent 的框架，提供类型安全的 Kotlin DSL 与流畅的 Java API；借助 Kotlin Multiplatform，Agent 可部署到 JVM / Android / iOS / JS / WasmJS 多端。其自我定位偏向「把接入好的 AI 变成可靠、可扩展、生产就绪的智能体系统」。
- **生态位差异（sureai 视角）**：Koog 的重心在**跨端 Agent 编排**（KMP 多端是它的护城河）；sureai 的重心在**多平台模型接入层**，且只跑在 JVM 上。二者不是替代关系：你完全可以用 Koog 做 Agent 编排，用 sureai 的零依赖客户端去接那些国产平台。

### Embabel

- **事实**：Embabel 是面向 JVM、用于构建生成式 AI 业务应用的智能体流程框架，由 Spring 创始人 Rod Johnson 发起（2025 年）；用 Kotlin 编写、对 Java 有自然的互操作体验，以 Actions / Goals / Conditions 建模智能体流程，强调类型安全与可测试性，支持把 Agent 暴露为 MCP Server，并内建 OpenTelemetry 可观测。公开报道称其构建在 Spring Boot 之上。
- **生态位差异（sureai 视角）**：Embabel 面向「把领域逻辑 + 工具 + LLM 编排成可测试的企业智能体业务流程」，抽象层级比接入层高；sureai 不做业务流程编排，只提供稳定、零依赖的多平台模型调用底座。做企业级智能体工作流选 Embabel；做不增重的多平台接入选 sureai。

## 选型建议

按你的实际约束选，不必二选一：

- **已经是 Spring Boot / Spring Cloud 团队** → 首选 **Spring AI**，生态、配置、监控都顺。
- **要一套通用 Java LLM 应用工具链（RAG/Chain/Agent 全家桶），不在意依赖体积** → **langchain4j**。
- **要做跨端（Android/iOS/JS/Wasm）智能体，偏好 Kotlin DSL** → **Koog**。
- **要做类型安全、可测试的企业智能体业务流程，且接受 Spring Boot** → **Embabel**。
- **想要零运行期第三方依赖、模块级隔离、静态工具类一行调用，尤其要把 23 个（含国产）平台轻量化接进已有 Java 进程** → **sureai**。
- **组合使用**：sureai 与上述框架可叠加——它是接入层，不抢占上层编排；把 sureai 的平台客户端喂给 Koog / Embabel / 自研 Agent 即可。

## 事实来源

竞品信息（访问日期 2026-10-08）：

- Spring AI 定位：<https://spring.io/projects/spring-ai> ；<https://docs.spring.io/spring-ai/reference/>
- Spring AI 2.0 基线（Spring Boot 4 / Framework 7 / Jackson 3）：<https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/>
- Spring AI 模块与 Maven Central：<https://docs.spring.io/spring-ai/reference/getting-started.html>
- langchain4j 定位（GitHub 自述 "Java version of LangChain"）：<https://github.com/langchain4j/langchain4j> ；<https://docs.langchain4j.dev/>
- Koog 定位（JetBrains JVM Agent 框架 + KMP 多端）：<https://www.jetbrains.com/koog/> ；<https://docs.koog.ai/>
- Koog 依赖坐标：<https://docs.koog.ai/quickstart/>
- Embabel 定位（JVM 智能体流程框架，Kotlin 编写、Java 互操作、Spring 创始人）：<https://docs.embabel.com/> ；<https://hub.embabel.com/getting-started>
- Embabel 基于 Spring Boot 的报道：<https://juejin.cn/post/7628799699520553003>（转引 The New Stack，属第三方报道，供参考）

sureai 自身数据来源：本仓库 [README](../README.md)（23 平台、零运行期第三方依赖、CLI/Gateway/RAG/Agent 全栈）、[docs/capabilities.md](capabilities.md)（能力面收口）。
