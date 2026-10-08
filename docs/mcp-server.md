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

## RAG 工具

RAG 查询工具不在 mcp-server 核心（避免依赖 `sure-ai-rag`）。如需暴露，请在 `sure-ai-rag` 内仿照 `SureAiTools` 编写适配类，把检索结果包成 `McpToolResult` 后 `server.registerTool(...)`。
