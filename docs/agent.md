# Agent 编排（ReAct 多工具循环）

sureai 在 `sure-ai-core` 的 Chat / Function Calling 原语之上，提供轻量 Agent 编排能力，
模块为 `sure-ai-agent`：工具注册中心、参数校验、ReAct 多工具循环。模块**只依赖
sure-ai-core**，与具体平台解耦——任意实现 `AiClient` 的客户端均可驱动。

## ReAct 原理

ReAct（Reason + Act）让模型在「思考」与「行动」之间循环：

```mermaid
graph LR
    A[用户问题] --> B[模型推理 Thought]
    B --> C{是否需要工具?}
    C -- 是 --> D[Action: tool_calls]
    D --> E[执行工具 Observation]
    E --> B
    C -- 否 --> F[最终答案]
```

- **Thought**：模型给出文本思考（本模块把它作为最终答案或下一轮输入）。
- **Action**：模型返回 `tool_calls`，编排器逐个执行。
- **Observation**：工具结果作为 `tool` 角色消息回灌模型，进入下一轮。

编排器内置两道防护：`maxIterations`（默认 10）与总 `timeout`（默认 120s），
防止模型无休止调用工具。

## 快速上手

```java
ToolRegistry registry = new ToolRegistry();

registry.register(ToolFunction.of("get_weather", "查询城市天气",
        """
        {"type":"object","required":["city"],
         "properties":{"city":{"type":"string"}}}
        """),
        args -> {
            String city = args.getString("city");
            return city + "：晴 26℃";
        });

ChatRequest base = ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.system("你是会用工具的助手")))
        .build();

ReActAgent agent = new ReActAgent(openAiClient, base, registry);
String answer = agent.run("西安天气如何？");
```

也可用 `AgentUtil` 全局注册中心（简单脚本场景）：

```java
AgentUtil.registerTool(ToolFunction.of("get_weather", "...", schema), args -> "...");
String answer = AgentUtil.react(client, base).run("西安天气如何？");
```

## 组件一览

| 组件 | 说明 |
|---|---|
| `ToolRegistry` | 工具注册中心：`register/unregister/getHandler/getToolSpecs`，线程安全，同名覆盖 |
| `ToolHandler` | 函数式接口 `String execute(JsonObject arguments)`；返回文本回灌模型，异常由编排器捕获 |
| `ToolExecutionResult` | 工具执行结果封装（`success(output)` / `failure(error)`） |
| `ToolArgumentValidator` | 按 JSON Schema 做 required + 基础类型校验（简化实现） |
| `ReActAgent` | ReAct 多工具循环编排器 |
| `AgentListener` | 事件回调（onThought/onToolCall/onToolResult/onFinish/onError），全部 default 空实现 |
| `AgentUtil` | 全局注册中心静态入口（双检锁单例） |
| `PlanExecuteAgent` | Plan-and-Execute 骨架（未实现，`run` 抛 `UnsupportedOperationException`） |

## 工具注册

```java
// 直接用 ToolFunction
registry.register(ToolFunction.of("calculate", "四则运算", schema),
        args -> String.valueOf(eval(args.getString("expr"))));

// 或直接用 ToolSpec
registry.register(ToolSpec.of(function), handler);

registry.unregister("calculate");
List<ToolSpec> tools = registry.getToolSpecs(); // 供 ChatRequest.tools
```

处理器入参是**已解析**的 `JsonObject`（`argumentsJson` 由编排器解析），直接 `getString/getInt` 即可；
返回字符串会作为 `tool` 角色消息回灌模型。处理器抛异常**不会中断编排**，
异常信息会被回灌模型让其自我修正（同时触发 `onError`）。

## 参数校验（简化 JSON Schema）

`ToolArgumentValidator` 只做两件事，**刻意不做**完整 JSON Schema 校验：

- `required` 字段必须存在且非 null；
- `properties[*].type` 基础类型判定：`string / integer / number / boolean / array / object`。

不实现 `pattern / minimum / maximum / enum / minLength` 等约束——这些交给工具处理器
业务层处理，避免引入完整 Schema 校验器。参数校验失败时，错误信息（列出缺失字段与
类型不匹配字段）会回灌模型。

## 事件回调

```java
AgentListener listener = new AgentListener() {
    @Override
    public void onToolCall(ToolCall call) { System.out.println("call " + call.name()); }
    @Override
    public void onToolResult(ToolCall call, String result) { System.out.println("-> " + result); }
    @Override
    public void onFinish(String answer) { System.out.println("final: " + answer); }
};
ReActAgent agent = new ReActAgent(client, base, registry, listener, 10, Duration.ofSeconds(120));
```

## 离线可运行示例（FakeAiClient）

不依赖任何真实模型，把首轮响应硬编码为 tool_calls、次轮为最终答案：

```java
AiClient fake = new AiClient() {
    private int turn;
    public ChatResponse chat(ChatRequest req) {
        this.turn++;
        if (this.turn == 1) {
            List<ToolCall> calls = List.of(
                    new ToolCall("c1", "get_weather", "{\"city\":\"西安\"}"));
            return ChatResponse.of("r1", "m",
                    List.of(Choice.of(0, ChatMessage.assistant(calls), "tool_calls")),
                    TokenUsage.of(1, 1, 2), null);
        }
        return ChatResponse.of("r2", "m",
                List.of(Choice.of(0, ChatMessage.assistant("西安晴 26℃"), "stop")),
                TokenUsage.of(1, 1, 2), null);
    }
    // name/chatStream/close 省略……
};
String answer = new ReActAgent(fake, base, registry).run("西安天气？");
```

完整可运行版本见 `sure-ai-examples` 的 `AgentDemo`（`mvn ... exec:java` 或
`ExamplesRunner agent`）。

## 平台兼容性

本模块只面向 `com.sure.ai.client.AiClient` 接口，Function Calling 协议的平台差异
（OpenAI tools / 通义 functions / 智谱 tools / 豆包 tools 等）已由各平台客户端在
序列化层处理。因此下列客户端均可直接驱动 `ReActAgent`：

- OpenAI / Azure / DeepSeek（OpenAI 兼容协议）
- 通义千问、智谱 GLM、豆包、Moonshot、文心、Anthropic、Gemini、Ollama

只要模型支持 Function Calling，编排层无需改动。

## 异常与防护

| 场景 | 行为 |
|---|---|
| 工具未注册 | 回灌 `"tool not found: <name>"`，模型下一轮可自我修正 |
| 参数校验失败 | 回灌缺失/类型错误字段列表 |
| 处理器抛异常 | 捕获后回灌异常信息，触发 `onError`，不中断循环 |
| `argumentsJson` 非法 JSON | 回灌解析错误提示 |
| 超过 `maxIterations` | 抛 `AiException("ReAct agent exceeded max iterations")` |
| 超过总 `timeout` | 抛 `AiTimeoutException` |
| 注册中心为空 | 退化为单次普通 chat，直接返回模型文本 |

## Plan-and-Execute 状态

`PlanExecuteAgent` 当前为**骨架**：构造器签名已对齐 `ReActAgent`，但 `run(...)` 抛
`UnsupportedOperationException`。完整设计思路（先让模型产出 JSON 步骤列表 → 逐步执行
→ 汇总）见类 JavaDoc，留待后续迭代。现阶段请使用 `ReActAgent`。
