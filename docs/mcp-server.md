# MCP Server（sure-ai-mcp-server）

把 sureai 统一的多平台 `AiClient` / `EmbeddingClient` / `ImageClient` 能力反向暴露为标准 [Model Context Protocol](https://modelcontextprotocol.io) Server。纯 JDK 实现，零第三方依赖。

- 模块坐标：`io.github.tasure:sure-ai-mcp-server`
- 传输：stdio（NDJSON）与 Streamable HTTP（`com.sun.net.httpserver`）
- 协议：兼容有状态规范 `2025-06-18`，并实验性适配无状态规范 `2026-07-28`
- 平台隔离：核心只依赖 `sure-ai-core` + `sure-ai-mcp`，不 import 任何平台模块

## 快速上手（stdio）

```java
import com.sure.ai.mcp.server.McpServer;
import com.sure.ai.mcp.server.McpServerUtil;
import com.sure.ai.mcp.server.SureAiTools;

// 1. 构建 server，注册 chat 工具（openAiClient 由你从任何平台模块创建）
McpServer server = new McpServer()
    .registerTool(SureAiTools.chatTool(openAiClient))
    .registerTool(SureAiTools.embedTool(openAiClient));

// 2. 以 stdio 启动（阻塞在后台读线程，供 Claude Desktop / mcp inspector 连接）
server.start(new StdioMcpServerTransport());
```

或用静态单例入口：

```java
McpServerUtil.init(new McpServer().registerTool(SureAiTools.chatTool(openAiClient)));
McpServerUtil.startStdio();
```

## HTTP 启动

```java
McpServer server = new McpServer().registerTool(SureAiTools.chatTool(openAiClient));
HttpMcpServerTransport http = new HttpMcpServerTransport(8080, "/mcp");
server.start(http);
System.out.println(http.endpoint()); // http://127.0.0.1:8080/mcp
// 关闭：server.close();
```

端口传 `0` 表示随机端口，用 `http.getPort()` 取实际端口。

## Claude Desktop 配置

`claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "sureai": {
      "command": "java",
      "args": ["-cp", "your-app.jar", "com.example.YourStdioServerMain"]
    }
  }
}
```

`YourStdioServerMain` 的 `main` 里构建 server 并 `server.start(new StdioMcpServerTransport())` 即可。

## 自定义工具注册

```java
McpServer server = new McpServer();
server.registerTool(new McpServerTool(
    "add",
    "两数相加",
    JsonSchemaGenerator.generate(AddRequest.class),   // 自动从 record 生成 inputSchema
    args -> new McpToolResult(false,
        List.of("sum=" + (args.getInt("a") + args.getInt("b"))))
));
```

处理器抛异常会被捕获并转为 `isError=true`，不会中断协议读循环。

## SureAiTools 工厂

| 工厂方法 | 工具名 | 入参 | 路由 |
| --- | --- | --- | --- |
| `chatTool(AiClient)` | `sureai.chat` | `platform` / `model` / `prompt` / `messages` / `temperature` / `maxTokens` | 单 client |
| `chatTool(Map<String,AiClient>)` | `sureai.chat` | 同上，`platform` 选 client | 按平台名路由 |
| `embedTool(EmbeddingClient)` | `sureai.embed` | `platform` / `model` / `input`(文本数组) / `text` | 单 client |
| `embedTool(Map<String,EmbeddingClient>)` | `sureai.embed` | 同上 | 按平台名路由 |
| `imageTool(ImageClient)` | `sureai.image` | `platform` / `model` / `prompt` / `size` | 单 client |
| `imageTool(Map<String,ImageClient>)` | `sureai.image` | 同上 | 按平台名路由 |

> 注：chat/embed/image 分别对应 core 的 `AiClient` / `EmbeddingClient` / `ImageClient` 三个接口（而非都用 `AiClient`），因为向量与图像在 core 中是独立抽象。

## 多 client 注册表

```java
Map<String, AiClient> clients = Map.of(
    "openai", openAiClient,
    "deepseek", deepSeekClient
);
server.registerTool(SureAiTools.chatTool(clients));
// 客户端调用 sureai.chat 时传 {"platform":"deepseek","model":"...","prompt":"..."} 即路由到 deepseek
```

## 协议兼容说明

- **有状态 2025-06-18**：响应 `initialize`（`protocolVersion` / `capabilities` / `serverInfo`），接受 `notifications/initialized`，HTTP 握手返回 `Mcp-Session-Id`。
- **无状态 2026-07-28**：当请求 `params._meta.io.modelcontextprotocol/protocolVersion` 存在、或方法为 `server/discover` 时，无需 initialize 直接处理；所有结果带 `resultType="complete"`、无状态结果带 `_meta.serverInfo`，list 结果带 `ttlMs` / `cacheScope`，实现 `server/discover`。

## 无状态扩展面（2026-07-28，v2.3.0 收口）

逐项对照 [2026-07-28 规范](https://modelcontextprotocol.io/specification/2026-07-28/changelog) 实现，规范依据见各项链接。

### MRTR（Multi Round-Trip Requests，多轮请求）

> 术语澄清：规范中的 MRTR 是 **Multi Round-Trip Requests**（[SEP-2322](https://modelcontextprotocol.io/specification/2026-07-28/changelog)），并非「multi-agent transport」。

- **已实现**：规范要求「所有结果都必须带 `resultType`」（`"complete"` 或 `"input_required"`）。本引擎现已对**所有** JSON-RPC 结果注入 `resultType="complete"`（含有状态 `initialize` / `ping` / `tools/call`）。
- **未实现**：`InputRequiredResult`（`resultType:"input_required"` + `inputRequests`）与客户端带 `inputResponses` 重试的完整多轮回调——这需要客户端侧的 elicitation/sampling 交互输入循环，本批不做，留待后续。

### subscriptions / listen

> 规范：[Subscriptions 模式](https://modelcontextprotocol.io/specification/draft/basic/patterns/subscriptions)。`subscriptions/listen` **取代**旧的 HTTP GET 端点与 `resources/subscribe` / `resources/unsubscribe`。

- **已实现**：
  - `subscriptions/listen` RPC：回显服务端承诺的订阅子集（`toolsListChanged` / `promptsListChanged` / `resourcesListChanged` / `resourceSubscriptions`），在 `com.sure.ai.mcp.server.McpSubscriptions` 中与传输层共享过滤语义；
  - HTTP 下以 `text/event-stream` 应答，首帧发 `notifications/subscriptions/acknowledged`，并在 `_meta.io.modelcontextprotocol/subscriptionId` 回显请求 id。
- **差异/未实现**：本批工具在启动期静态注册、无运行期列表变更源，故 ack 后随附一条 graceful-close 完成结果即结束流，未实现持续推送 `notifications/tools/list_changed` 等的真正长连接；`resources/subscribe` / `resources/unsubscribe` 按规范已移除（方法未找到 -32601）。

### Mcp-Method / Mcp-Name 请求头

> 规范：[Streamable HTTP / Request Metadata](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)。

- **客户端（sure-ai-mcp）已实现**：`StreamableHttpMcpTransport` 每次 POST 镜像 `Mcp-Method`（必带）与 `Mcp-Name`（`tools/call` / `resources/read` / `prompts/get`）；非 ASCII 名走 `=?base64?...?=` 哨兵格式（见 `McpHeaders.encode/decode`）。
- **服务端（sure-ai-mcp-server）已实现**：请求若携带 `Mcp-Method` / `Mcp-Name`，传输层 Base64 解码后与 body 比对，不一致返回 `400` + JSON-RPC `-32020 HeaderMismatch`。
- **差异**：未携带这些头的旧请求一律放行（向后兼容，默认不强制）；`MCP-Protocol-Version` 头的强制校验与 `x-mcp-header` / `Mcp-Param-*` 镜像未实现。

### OAuth 客户端凭证 / 受保护资源元数据

> 规范：[Authorization Server Discovery](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery) + [RFC 9728](https://datatracker.ietf.org/doc/html/rfc9728/)。

- **已实现**：HTTP 传输按 RFC 9728 在 `/.well-known/oauth-protected-resource[&lt;path&gt;]` 暴露受保护资源元数据（`resource` / `authorization_servers` / `scopes_supported`），可用 `authorizationServers(...)` / `scopesSupported(...)` 配置：
  ```java
  HttpMcpServerTransport http = new HttpMcpServerTransport(8080, "/mcp")
      .authorizationServers("https://as.example.com")
      .scopesSupported("mcp:tools");
  ```
- **差异/未实现**：仅暴露元数据，不做真实 token 校验/客户端凭证换 token（需独立授权服务器，超出本批纯 JDK、零外部依赖范围）。

## 无状态深化（2.9.0）

> 以下能力于 2.9.0 对照官方规范补齐，核实日 2026-10-19（正式基线
> [2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/changelog)，
> `/draft` 镜像该基线）。公共 API 只增不改，旧的 `registerTool(...)` 静态注册与
> 有状态 `initialize` 握手继续可用。

### 动态工具来源（McpToolSource）

除 `server.registerTool(...)` 静态注册外，可注册一个或多个动态工具来源
（`com.sure.ai.mcp.server.McpToolSource`，函数式接口 `List<McpServerTool> tools()`），
`tools/list` 与 `tools/call` 会把静态表与全部动态来源合并：

```java
server.registerToolSource(() -> List.of(
    McpServerTool.builder("dyn", args -> new McpToolResult(false, List.of("...")))
        .description("动态工具")
        .title("动态工具标题")
        .annotations(McpToolAnnotations.builder()
            .readOnlyHint(true)     // 只读
            .idempotentHint(true)   // 幂等
            .openWorldHint(true)    // 访问外部世界
            .build())
        .meta(meta)                 // 私有元数据 → _meta
        .build()));
```

合并语义：按工具名去重，**静态表优先，动态来源之间先注册优先**；来源返回 `null`
清单或清单含 `null` 元素均容错跳过。

### 标准工具注解与私有元数据

`McpServerTool` 新增 `title` 与 `McpToolAnnotations`
（`readOnlyHint` / `destructiveHint` / `idempotentHint` / `openWorldHint`），序列化进
标准 `tools/list` 的 `title` 与 `annotations` 字段；标签、来源（local/mcp）、风险级别
等**非标准字段必须放入 `_meta`**，不得伪造核心字段（旧四参构造器保留）。

### 协议版本头与错误码

- **`MCP-Protocol-Version` 请求头**：与 `params._meta` 声明的版本不一致 → `400` +
  `-32020 HeaderMismatch`；取值不是服务端支持版本（`2025-06-18` / `2026-07-28`）→
  `400` + `-32022 UnsupportedProtocolVersion`，错误 `data` 含 `requested` / `supported`。
  帧级 `_meta` 版本校验同样生效（覆盖 stdio 等不经 HTTP 头的传输）。
- **未知方法**：HTTP 响应 `404` + JSON-RPC `-32601 MethodNotFound`。
- **未知工具 / 资源 / 技能**：统一 `-32602 InvalidParams`（取代旧的 isError 工具结果
  与旧草案 `-32002`）；工具存在但 handler 抛错仍按标准返回 `isError=true` 的工具结果。
- 新增错误码常量：`McpError.HEADER_MISMATCH(-32020)`、
  `MISSING_REQUIRED_CLIENT_CAPABILITY(-32021)`、`UNSUPPORTED_PROTOCOL_VERSION(-32022)`。

### 缓存提示（CacheableResult）

无状态请求（`_meta` 声明 2026-07-28 或 `server/discover`）的结果带 `ttlMs`
（默认 3600000ms，可配）与 `cacheScope`（`public`/`private`，可配），并在 `_meta`
自报 `io.modelcontextprotocol/serverInfo`；所有结果保证带 `resultType`（缺省 complete）。

## Skills over MCP 扩展（2.9.0）

> 官方 **Final 扩展** `io.modelcontextprotocol/skills`（[SEP-2640，2026-09-13 Final]
> (https://modelcontextprotocol.io/extensions/skills/overview)），并非项目自创。
> 注册技能来源后，`server/discover` 同时声明 `resources` 能力与
> `capabilities.extensions["io.modelcontextprotocol/skills"]`（不冒充核心 skills 能力）。

### 实现 McpSkillSource

技能来源 SPI（`com.sure.ai.mcp.server.skill.McpSkillSource`）：

```java
public interface McpSkillSource {
    List<McpSkillInfo> listSkills();                 // 技能清单
    McpSkillInfo getSkill(String uri);               // 单个技能详情
    String readResource(String uri);                 // 读资源文本（不存在返回 null）
    default boolean directoryRead() { return false; }                 // 可选：目录枚举
    default List<McpSkillResource> listDirectory(String uri) { ... }  // 仅上一项为 true
}
```

- `McpSkillInfo`：`uri` + `frontmatter`（JsonObject）+ `resources`
  （`List<McpSkillResource>`，或字符串 `"dynamic"` 表示资源动态枚举）；
- `McpSkillResource`：`uri` / `digest`（`sha256:...`）/ `size`（字节数）。

### 协议方法

| 方法 | 行为 |
|---|---|
| `skills/list` | 返回 `{resultType:"complete", skills:[{uri,frontmatter,resources}], nextCursor?, ttlMs, cacheScope}`；`cursor` 为 Base64 URL 编码的分页偏移，页大小可配（`skillPageSize`，默认 100），非法 / 越界 cursor → `-32602` |
| `skills/get` | 参数 `{uri}`，返回 `{skill:{...同清单条目}}`；未知 URI → `-32602`，缺 uri → `-32602` |
| `resources/read` | 正文走标准资源读取，URI 形如 `skill://<name>/SKILL.md`；`SKILL.md` 与 `.md` 资源为 `text/markdown`，其余为 `text/plain`；未知 URI / 非 `skill://` scheme → `-32602` |
| `resources/directory/read` | 仅在来源 `directoryRead()` 为 true 时可用，否则 `-32602`；枚举内部失败 → `-32602`，内部错误 → `-32603` |
| `server/discover` | 注册了技能来源才声明 `resources` 与 `extensions`；未注册来源时调用 `skills/*` 返回 `-32601` |

`skills/list` / `skills/get` 为 CacheableResult，无状态请求携带 `ttlMs` / `cacheScope`。
官方建议单个技能 ≤512 文件 / 16MiB，`SKILL.md` 父目录末段须等于技能 `name`。

### 与 Agent Skills / 工具市场打通

`sure-ai-examples` 提供两个零依赖桥接适配器，把 `sure-ai-agent` 的成果直接经 MCP 暴露：

- `com.sure.ai.examples.mcp.CatalogMcpToolSource`：`ToolCatalog` → `McpToolSource`，
  标签 / 来源 / 风险级别 / 版本 / server 入 `_meta`，处理器异常映射 `isError`；
- `com.sure.ai.examples.mcp.SkillMcpSkillSource`：`SkillLoader` 加载的 `Skill` →
  `McpSkillSource`，为 `SKILL.md` 与登记的 L3 资源计算 `sha256` 摘要与字节数。

可运行 demo：`AgentEcosystemMcpDemo`（`ExamplesRunner` 的 `agentmarket` case），
离线串起 discover → tools/list → tools/call → skills/list → skills/get →
resources/read 全链路，零真实网络。

### 仍为实验性 / 未落地项（如实说明）

- **MRTR 完整多轮交互**（`input_required` + `inputRequests` + 客户端带 `inputResponses`
  重试，仅允许 prompts/get、resources/read、tools/call）：规范已定稿（SEP-2322），
  但需要客户端侧交互输入循环，本版本仅落地 `resultType` 枚举与 complete 缺省处理，
  未实现服务端发起的 input_required 多轮。
- **subscriptions/listen 持续推送**：当前 ack 后 graceful-close，工具/技能在启动期静态
  装配、无运行期列表变更源，未实现真正长连接推送。
- **OAuth 2.1 完整授权流程**：仅按 RFC 9728 暴露 `/.well-known/oauth-protected-resource`
  元数据；token 校验、DCR（已弃用，转向 Client ID Metadata Documents）等不实现。
- 2026-07-28 已弃用面不恢复：`initialize` 握手（仅向后兼容保留）、`Mcp-Session-Id`、
  `Last-Event-ID`、Roots/Sampling/Logging、旧 HTTP+SSE 传输。

## RAG 工具

RAG 查询工具不在 mcp-server 核心（避免依赖 `sure-ai-rag`）。如需暴露，请在 `sure-ai-rag` 内仿照 `SureAiTools` 编写适配类，把检索结果包成 `McpToolResult` 后 `server.registerTool(...)`。
