# Agent 生态：Skills / 子 Agent 委派 / 工具市场（2.9.0）

2.9.0「Agent 生态」在 `sure-ai-agent` 的 ReAct / PlanExecute 之上补齐三块能力，对标
LangChain4j AgenticServices 与 Anthropic Agent Skills：

- **Agent Skills**：以 `SKILL.md` 为载体的技能包，三级渐进式加载，技能即工具；
- **子 Agent 层级式委派**：一个 Agent 作为另一个 Agent 的工具被调用（AgentAsTool），
  或把控制权整体移交（Handoff），带嵌套深度 / 环检测与事件冒泡；
- **工具注册中心市场**：在 `ToolRegistry` 之上建 `ToolCatalog`，支持标签 / 关键词发现、
  风险级别、MCP 工具聚合、命名空间与 SPI 贡献。

三者均在模块 `sure-ai-agent` 内，**只依赖 sure-ai-core，零第三方运行期依赖**；
公共 API 只增不改。技能 frontmatter 解析为手写 YAML 子集（不引入 snakeyaml）。

---

## 一、Agent Skills

### 1.1 规范背景（已联网核实）

实现遵循 Anthropic Agent Skills 官方规范（核实来源：
`https://docs.anthropic.com/en/docs/agents-and-tools/agent-skills/overview`
与 best-practices，核实日 2026-10-19）：

- **frontmatter 必填字段**：`name`、`description`。
  - `name`：≤64 字符，仅小写字母 / 数字 / 连字符；`anthropic`、`claude` 为保留词；
  - `description`：非空、≤1024 字符、禁 XML 尖括号；应写清「做什么 + 何时触发」。
- **可选字段**：`license`、`compatibility`（依赖 / 环境要求）、`metadata`、
  `allowed-tools`（可移植打包允许集合）。**规范中没有 `version` 字段**，本实现不提供。
- **目录结构**：`SKILL.md`（必需）+ 可选 `scripts/`（可执行脚本）、
  `references/`（按需读入上下文的文档）、`assets/`（模板 / 图标等产出素材），路径只用正斜杠。
- **三级渐进式加载**：
  - **L1 元数据**：name / description（常驻系统提示，约 100 token）；
  - **L2 正文**：`SKILL.md` 正文，技能被触发时才读入；
  - **L3 资源**：`references/` / `assets/` 等，按需读取，脚本与代码不进入上下文。

### 1.2 技能目录结构

```
skills/
└── pdf-processing/
    ├── SKILL.md
    ├── references/
    │   └── extract.md
    └── assets/
        └── template.txt
```

`SKILL.md` 示例：

```markdown
---
name: pdf-processing
description: 处理 PDF 文件。当用户需要提取、拆分或合并 PDF 时使用。
license: Apache-2.0
---

# PDF 处理

1. 需要提取表格时，读取 references/extract.md；
2. 模板位于 assets/template.txt。
```

### 1.3 加载技能

`SkillLoader` 支持本地目录、classpath 与显式 jar 三种来源：

```java
// 1) 本地目录：参数可为单个技能目录（直接挂该技能），或含多个技能子目录的根目录
List<Skill> skills = SkillLoader.load(Path.of("skills"));

// 2) classpath：从 classpath 根路径加载（支持「根下若干技能子目录」与「根本身即单技能」两种布局）
List<Skill> skills = SkillLoader.loadFromClasspath("skills");

// 3) 显式 jar：标准 URLClassLoader 不保证回吐 jar 根 URL，jar 打包技能走显式入口
List<Skill> skills = SkillLoader.loadJar(Path.of("skills.jar"), "skills");
```

加载行为约定：

- 构造期只解析 L1 manifest，不读正文与资源；
- 同名冲突、frontmatter 非法严格抛 `SkillLoadException`，**不静默跳过**；
- 所有资源相对路径经 `SkillPaths.safeRelative` 校验，拒 `..`、绝对路径、反斜杠、
  NUL 等路径穿越输入（非法路径抛 `SkillResourceException`）。

### 1.4 技能作为工具暴露给 Agent

`SkillTool` 把一个技能映射为一个工具：工具名 = 技能名，工具描述 = `description`；
无参调用返回 L2 正文回灌模型，传 `resource` 参数返回指定 L3 文本资源。

```java
ToolRegistry registry = new ToolRegistry();

// 一键注册全部技能
SkillTool.registerAll(SkillLoader.load(Path.of("skills")), registry);

// 或单个注册
Skill skill = SkillLoader.load(Path.of("skills/pdf-processing")).get(0);
SkillTool.register(skill, registry);

// 随后 ReAct 循环经现有 getToolSpecs() 自动发现这些技能
ReActAgent agent = new ReActAgent(client, baseRequest, registry);
String answer = agent.run("帮我提取这份 PDF 的表格");
```

模型调用技能工具时：

- 无参 → 返回 `SKILL.md` 正文（L2）；
- `{"resource": "references/extract.md"}` → 返回该引用资源文本（L3）。

### 1.5 边界与偏差（如实说明）

- **`scripts/` 不执行**：本版本只返回指令与文本资源，不运行脚本。规范中脚本经宿主
  bash 执行，需要独立沙箱能力，执行面留待后续版本。
- **`allowed-tools` 仅作元数据保留**：本层不做工具白名单强制（拦截属 Agent 调度策略，
  不是技能装载职责）。
- **jar 技能需显式 `loadJar`**：classpath 自动扫描只覆盖 file 展开目录；
  官方规范只规定文件系统目录 / zip 上传，未规定 jar 内扫描格式，该约定为本工程自定义。
- `license` / `compatibility` 仅解析透传，不做许可证合法性校验或环境探测。

---

## 二、子 Agent 层级式委派

既有的 `AgentOrchestrator` 是「任务拆分 → 并行执行 → 结果聚合」的 fan-out/fan-in
模型；本节的层级式委派解决的是另一个问题：**一个 Agent 把某类子任务整体交给另一个
专职 Agent 处理**。

### 2.1 两种委派语义

| 语义 | 类 | 行为 |
|---|---|---|
| **委派后父继续**（sub-agent as tool） | `AgentAsTool` | 子 Agent 作为一个工具被调用，子答案作为 observation 回灌，父模型继续推理 |
| **控制权移交**（handoff） | `HandoffAgent` + `HandoffTool` | 调用即把控制权交给目标 Agent，目标答案即最终答案（结果带 `[HANDOFF]` 前缀），父不再继续推理 |

### 2.2 把 ReActAgent 适配为可委派句柄

`ReActAgent` / `PlanExecuteAgent` 是 final 类且 listener 构造期注入，委派层通过最小
接口 `AgentRunner` 适配，不修改既有类：

```java
ReActAgent researcher = new ReActAgent(researchClient, base, new ToolRegistry());
AgentRunner runner = AgentRunner.of(researcher, "researcher", "联网检索专员");

// 自定义句柄也可直接用函数式适配
AgentRunner simple = AgentRunner.of("echo", "回显专员", task -> "收到：" + task);
```

### 2.3 AgentAsTool：委派后父继续

`AgentAsTool` 通过 `ChildAgentFactory` 在委派发生时现场构造子 Agent——这是因为子 Agent
的事件监听器需要在调用时才能绑定到「冒泡 sink」。固定子 Agent 可用
`ChildAgentFactory.fixed(runner)` 包装。

```java
ToolRegistry registry = new ToolRegistry();

AgentAsTool researchTool = AgentAsTool
    .builder("researcher", "需要联网检索资料时委派给检索专员",
        ChildAgentFactory.fixed(researchRunner))
    .maxDepth(5)                 // 最大嵌套深度，默认 5
    .parentListener(parentListener)   // 子 Agent 事件冒泡到该监听者
    .memoryPolicy(MemoryPolicy.ISOLATED) // 默认 ISOLATED；SHARED 需双方共享同一 memory
    .timeout(Duration.ofSeconds(60))   // 可选单次超时（虚拟线程执行，仅叶子级单次委派）
    .build();
researchTool.register(registry);

ReActAgent supervisor = new ReActAgent(superClient, base, registry);
String answer = supervisor.run("调研一下 MCP 协议并给出结论");
```

### 2.4 Handoff：控制权移交

```java
HandoffAgent billing = HandoffAgent.of(billingRunner, "billing", "账单与退款专员");
// 注册后父模型调用 billing 工具即整体移交，billing 的答案就是最终答案
HandoffTool.register(billing, registry, "涉及账单 / 退款问题时交给账单专员");
```

### 2.5 嵌套深度与环检测

- 同一委派链中维护祖先 Agent id 栈（`DelegationScope`，线程局部 + try-finally 出栈）；
- 同链重复委派同一 Agent（直接环或间接环）抛 `DelegationCycleException`；
- 嵌套深度超过上限抛 `DelegationDepthExceededException`（默认 5，逐层收紧）；
- 子 Agent 抛出的异常包装为 `DelegationException`（保留 cause，不吞）；
- 单次委派超时抛 `DelegationTimeoutException`。

### 2.6 上下文传递与事件冒泡

- `DelegationContext` 携带 chainId、depth、sourceAgentId、parentTask、ancestorIds、
  memoryPolicy、timeout，每下潜一层 `child()` 递进；
- `ForwardingDelegationListener` 把子 Agent 的全部 `AgentListener` 回调转发给父监听者；
  `DelegatedAgentEvent` 包装原始事件并附来源 Agent / 深度 / 链 id；
- 父监听者为 null 时不冒泡；父监听者自身抛异常被隔离，不影响子 Agent 执行；
- 记忆策略默认 `ISOLATED`（专职子 Agent 的中间历史不污染父上下文）；需要共享时由
  调用方在构造父子 Agent 时传入同一个 memory 实例并声明 `MemoryPolicy.SHARED`。

### 2.7 与 ReAct / PlanExecute 组合

`AgentRunner` 同时适配 `ReActAgent` 与 `PlanExecuteAgent`：父可以是 ReAct 或
PlanExecute，子也可以是二者任一种，组合方式与普通工具注册一致。

---

## 三、工具注册中心市场

`ToolCatalog` 在 `ToolRegistry`（运行期调用面）之上增加「市场面」：能力清单、
多维发现、风险标注、MCP 聚合与命名空间。**不破坏现有 `ToolRegistry` API**——
ToolCatalog 可独立使用，也可委托一个 ToolRegistry 做镜像。

### 3.1 注册工具并标注市场元数据

```java
ToolCatalog catalog = new ToolCatalog();   // 也可 new ToolCatalog(existingRegistry)

catalog.register(
    ToolFunction.of("search_orders", "按订单号检索订单",
        "{\"type\":\"object\",\"required\":[\"orderId\"],"
        + "\"properties\":{\"orderId\":{\"type\":\"string\"}}}"),
    args -> orderService.search(args.getString("orderId")),
    ToolMetadata.builder("search_orders")
        .description("按订单号检索订单")
        .addTag("order")
        .addTag("search")
        .version("1.2.0")
        .riskLevel(RiskLevel.LOW)        // LOW / MEDIUM / HIGH / CRITICAL
        .source(ToolSource.LOCAL)        // LOCAL / MCP
        .docUrl("https://example.com/docs/orders")
        .build());
```

> `RiskLevel` 只是静态标注，不替代高风险工具的 `HighRiskApprovalPolicy` 人工审批。

### 3.2 发现与过滤

```java
List<ToolEntry> byTag     = catalog.findByTag("order");     // 标签大小写不敏感
List<ToolEntry> byKeyword = catalog.findByKeyword("订单");   // 命中名称 / 描述 / 标签
List<ToolEntry> local     = catalog.filterBySource(ToolSource.LOCAL);
List<ToolEntry> highRisk  = catalog.filterByRiskLevel(RiskLevel.HIGH);
List<ToolEntry> all       = catalog.list();                 // 按名称排序的快照
```

### 3.3 冲突策略

默认同名注册为 `OVERWRITE`（后注册覆盖，与底层 `ToolRegistry` 的覆盖语义一致），可在
构造后统一调整默认策略，或在单次注册时显式指定：

```java
catalog.conflictStrategy(ConflictStrategy.FAIL);      // 同名抛 IllegalStateException
catalog.conflictStrategy(ConflictStrategy.OVERWRITE); // 后注册覆盖（默认）
catalog.conflictStrategy(ConflictStrategy.RENAME);    // 自动改名 name__1，新旧共存
catalog.register(entry, ConflictStrategy.RENAME);     // 单次注册显式指定
```

### 3.4 MCP 工具统一聚合与命名空间

`sure-ai-mcp` 的 `McpToolAdapter` 提供把 MCP server 工具聚合进市场的重载；
开启命名空间后工具名形如 `mcp.<server>.<tool>`，并自动标注 `source=MCP` 与所属 server：

```java
McpClient client = ...; // 零真实网络测试用本地回环 mock
McpToolAdapter.registerAllTools(client, catalog, "github",
    ConflictStrategy.FAIL, /* namespace */ true);
```

> MCP 初始化握手不回传 / 不保留 serverInfo，服务端身份无法自发现，
> 故 `serverLabel`（上例为 `"github"`）必须由调用方显式提供，框架不编造。

### 3.5 导出给运行期注册中心

市场用于发现与选型，实际 ReAct 运行仍消费 `ToolRegistry`：

```java
catalog.exportTo(registry);                                   // 全部导出
catalog.exportTo(registry, entry -> entry.metadata()
    .riskLevel() == RiskLevel.LOW);                           // 谓词筛选导出
catalog.exportNames(Set.of("search_orders"), registry);       // 按名导出（未知名抛异常）
```

### 3.6 远程清单注册 SPI

新工具可通过标准 `ServiceLoader` 贡献，无需改主代码：

1. 实现 `ToolProvider`，返回若干 `ToolContribution`（function + handler + metadata）；
2. 在 jar 的 `META-INF/services/com.sure.ai.agent.tool.catalog.ToolProvider` 登记实现类；
3. 启动时加载：

```java
ToolProviders.loadInto(catalog);   // 或 List<ToolContribution> all = ToolProviders.load();
```

内置零网络示例 `BuiltinSampleToolProvider` 提供 `sample.echo` / `sample.ping` 两个工具。
**远程清单仅提供注册接入点，框架不发起真实网络请求**；拉取远程清单属应用层职责。

---

## 四、把工具市场与技能经 MCP 暴露

`sure-ai-mcp-server` 提供两个 SPI：`McpToolSource`（动态工具来源）与
`McpSkillSource`（Skills 扩展来源）。桥接适配器（ToolCatalog / SkillLoader → MCP）
位于 `sure-ai-examples` 的 `com.sure.ai.examples.mcp` 包，可运行 demo 为
`AgentEcosystemMcpDemo`（`ExamplesRunner` 的 `agentmarket` case）：

```java
McpServer server = new McpServer().serverInfo("my-agent", "2.9.0")
    .registerToolSource(new CatalogMcpToolSource(catalog))
    .registerSkillSource(new SkillMcpSkillSource(
        SkillLoader.loadFromClasspath("skills")));
```

协议细节（discover、skills/list、skills/get、resources/read、缓存提示、错误码、
`MCP-Protocol-Version` 头）见 [mcp-server.md](mcp-server.md) 的「无状态深化（2.9.0）」
与「Skills over MCP 扩展」章节。

---

## 五、规范来源

- Agent Skills 总览：`https://docs.anthropic.com/en/docs/agents-and-tools/agent-skills/overview`
- Agent Skills 最佳实践：`https://docs.anthropic.com/en/docs/agents-and-tools/agent-skills/best-practices`
- Skills over MCP（官方 Final 扩展 SEP-2640）：`https://modelcontextprotocol.io/extensions/skills/overview`
- MCP 无状态规范（2026-07-28 基线）：`https://modelcontextprotocol.io/specification/draft/server/discover`
