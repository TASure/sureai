# MCP Server

> English page · Chinese source: [../mcp-server.md](../mcp-server.md). Module: `sure-ai-mcp-server`.

Expose sureai's multi-platform `AiClient` / `EmbeddingClient` / `ImageClient` as a
standard [Model Context Protocol](https://modelcontextprotocol.io) server. Pure JDK,
zero third-party dependencies. Transports: stdio (NDJSON) and Streamable HTTP
(`com.sun.net.httpserver`).

## Quick start (stdio)

```java
import com.sure.ai.mcp.server.McpServer;
import com.sure.ai.mcp.server.SureAiTools;

// openAiClient is built from any platform module
McpServer server = new McpServer()
    .registerTool(SureAiTools.chatTool(openAiClient))
    .registerTool(SureAiTools.embedTool(openAiClient));

// blocks on a background read thread; connect with Claude Desktop / MCP inspector
server.start(new StdioMcpServerTransport());
```

## HTTP transport

```java
McpServer server = new McpServer().registerTool(SureAiTools.chatTool(openAiClient));
HttpMcpServerTransport http = new HttpMcpServerTransport(8080, "/mcp");
server.start(http);
System.out.println(http.endpoint());   // http://127.0.0.1:8080/mcp
```

Pass port `0` for a random port, then read `http.getPort()`.

## Built-in tools

| Factory | Tool name | Routes |
|---|---|---|
| `SureAiTools.chatTool(AiClient)` | `sureai.chat` | single client |
| `SureAiTools.chatTool(Map<String,AiClient>)` | `sureai.chat` | by `platform` argument |
| `SureAiTools.embedTool(EmbeddingClient)` | `sureai.embed` | single client |
| `SureAiTools.imageTool(ImageClient)` | `sureai.image` | single client |

Multi-client routing:

```java
Map<String, AiClient> clients = Map.of("openai", openAiClient, "deepseek", deepSeekClient);
server.registerTool(SureAiTools.chatTool(clients));
// a caller passing {"platform":"deepseek","model":"...","prompt":"..."} routes to deepseek
```

## Custom tools

```java
server.registerTool(new McpServerTool(
    "add",
    "Add two integers",
    JsonSchemaGenerator.generate(AddRequest.class),   // record → inputSchema
    args -> new McpToolResult(false,
        List.of("sum=" + (args.getInt("a") + args.getInt("b"))))
));
```

Handler exceptions are caught and returned as `isError=true` — the protocol read loop never breaks.

## Claude Desktop config

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

## Protocol notes

Compatible with the stateful `2025-06-18` spec; experimentally adapted to the
stateless `2026-07-28` spec. RAG query tools are intentionally not in this core
module (to avoid a dependency on `sure-ai-rag`) — build an adapter inside
`sure-ai-rag` wrapping retrieval results in `McpToolResult` if you need them.

## Next steps

- [Agent](./agent.md) · [Structured output](./structured-output.md) · [CLI](./cli.md)
