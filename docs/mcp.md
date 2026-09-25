# MCP（Model Context Protocol）客户端

`sure-ai-mcp` 是 sureai 的 MCP 客户端模块：用**纯 JDK** 实现 JSON-RPC 2.0 消息、
stdio 子进程与 Streamable HTTP 两种传输，完成 `initialize` 握手后即可调用远端
MCP server 的 tools / resources / prompts，并能把远端工具**一键注册进
`sure-ai-agent` 的 `ToolRegistry`**，与 ReActAgent 组合使用。

- 运行期第三方依赖：**仅有** `io.github.tasure:sure-core`（工具类）；
  JSON 复用 `com.sure.ai.internal.json`，SSE 复用 `com.sure.ai.internal.http`，
  HTTP 用 JDK `HttpClient`，进程管理用 `ProcessBuilder`。
- 不引入 AWS SDK / Jackson / OkHttp / reactive 等任何库。

## 协议与传输

MCP 报文是 JSON-RPC 2.0：

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

请求带 `id`（从 1 自增，`AtomicLong`），通知无 `id`，响应按 `id` 配对。

### stdio 传输

`ProcessBuilder` 启动 MCP server 子进程，通过 stdin/stdout 交换 NDJSON（每行一帧），
stderr 后台排空丢弃。适合本地命令型 server（如 `npx -y @modelcontextprotocol/server-filesystem`、
`python server.py`）。

### Streamable HTTP 传输

JDK `HttpClient` POST 单端点，请求头 `Accept: application/json, text/event-stream`：

- 响应 `application/json` → 直接解析为单个 JSON-RPC 响应；
- 响应 `text/event-stream` → 用 `SseLineReader` 聚合 SSE，取 `id` 匹配的事件数据；
- 首次响应若带 `Mcp-Session-Id` 头，缓存后在后续请求原样带回。

## 快速上手

```java
import com.sure.ai.mcp.McpClient;
import com.sure.ai.mcp.McpUtil;
import com.sure.ai.internal.json.Json;

// stdio：启动本地 MCP server
try (McpClient client = McpUtil.stdio("node", "server.js").build()) {
    client.toolsList().forEach(t -> System.out.println(t.name()));

    var args = Json.object();
    args.put("path", "/tmp");
    var r = client.toolsCall("read_file", args);
    System.out.println(r.asText());
}

// Streamable HTTP
try (McpClient client = McpClient.http("http://127.0.0.1:8000/mcp").build()) {
    client.promptsList();
}
```

`McpClient` 实现 `AutoCloseable`，`close()` 会发 `notifications/closed` 并关闭传输。

## 能力 API

| 方法 | 说明 |
| --- | --- |
| `toolsList()` | `List<McpTool>`（name / description / inputSchema） |
| `toolsCall(name, arguments)` | `McpToolResult`（content[].text + isError） |
| `resourcesList()` / `resourcesRead(uri)` | 资源列举与读取 |
| `promptsList()` / `promptsGet(name, args)` | 提示模板列举与渲染 |
| `call(method, params)` | 任意原始 JSON-RPC 方法 |

## 把 MCP 工具接入 Agent

`McpToolAdapter.registerAllTools(client, registry)` 把远端 server 的全部工具包装成
`ToolHandler` 注册进 `ToolRegistry`——MCP tool 的 `inputSchema` 原样序列化为 JSON Schema
字符串作为 `ToolFunction.parameters`，模型调用时 arguments 透传，返回 text 拼接回灌模型。

```java
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.mcp.McpClient;
import com.sure.ai.mcp.McpToolAdapter;
import com.sure.ai.model.ChatRequest;

ToolRegistry registry = new ToolRegistry();
try (McpClient mcp = McpUtil.stdio("node", "filesystem-server.js").build()) {
    int n = McpToolAdapter.registerAllTools(mcp, registry);
    System.out.println("已注册 " + n + " 个 MCP 工具");

    // registry 现在可直接驱动 ReActAgent
    ChatRequest base = ChatRequest.builder().model("gpt-4o").build();
    // ReActAgent agent = new ReActAgent(openAiClient, base, registry);
    // agent.run("读一下 /tmp 目录");
}
```

## 零真实网络

本模块测试全程不发真实网络：HTTP 用 `com.sun.net.httpserver.HttpServer` 本地 mock
（含 SSE 响应与 `Mcp-Session-Id` 维护）；stdio 用 `PipedInputStream/PipedOutputStream`
注入流测试帧配对；端到端测试在 `src/test/resources/echo-mcp-server/EchoMcpServer.java`
放一个纯 JDK echo server，用 `java EchoMcpServer.java` 单文件源码模式起子进程，
走完整 initialize + tools/list + tools/call NDJSON 回环。
