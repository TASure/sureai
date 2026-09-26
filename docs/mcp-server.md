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
- **无状态 2026-07-28（实验性）**：当请求 `params._meta.io.modelcontextprotocol/protocolVersion` 存在、或方法为 `server/discover` 时，无需 initialize 直接处理；结果带 `resultType="complete"`、`_meta.serverInfo`，list 结果带 `ttlMs` / `cacheScope`，实现 `server/discover`。未覆盖 MRTR、`subscriptions/listen`、OAuth 等扩展面。

## RAG 工具

RAG 查询工具不在 mcp-server 核心（避免依赖 `sure-ai-rag`）。如需暴露，请在 `sure-ai-rag` 内仿照 `SureAiTools` 编写适配类，把检索结果包成 `McpToolResult` 后 `server.registerTool(...)`。
