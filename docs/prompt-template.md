# Prompt 模板与查询改写

`sure-ai-rag` 提供零依赖的轻量提示词模板与查询改写能力，分别位于
`com.sure.ai.rag.prompt` 与 `com.sure.ai.rag.rewriter` 两个包。

> 设计约束：运行期**不引入任何模板引擎库**（Freemarker / Velocity 等），
> 仅基于正则 `\{(\w+)\}` 做占位符替换；`sure-ai-rag` 运行期只依赖
> `sure-ai-core` + `sure-ai-core` 模型。

## PromptTemplate

单段文本模板，以 `{varName}` 作为占位符。

### 占位符语法

| 写法 | 含义 |
| --- | --- |
| `{name}` | 必填占位符 |
| `{role=访客}` | 带默认值的占位符；变量未提供时使用默认值 `访客` |

- 变量值调用 `toString()` 渲染；`null` 替换为空串。
- 变量名只允许字母、数字、下划线（`\w+`）。

### 严格模式

默认（非严格）：未提供的变量**原样保留** `{name}`，多余变量被忽略。
开启严格模式后，缺变量或模板中不存在的多余变量都会抛 `IllegalArgumentException`。

```java
import com.sure.ai.rag.prompt.PromptTemplate;
import java.util.Map;

// 基本替换
String out = PromptTemplate.fromString("你好 {name}，今年 {age} 岁")
        .render(Map.of("name", "张三", "age", 30));
// -> 你好 张三，今年 30 岁

// 可变参数渲染
String out2 = PromptTemplate.fromString("{name} 的年龄是 {age}")
        .render("name", "李四", "age", "28");

// 默认值：未提供 role 时使用「访客」
String out3 = PromptTemplate.fromString("当前身份：{role=访客}")
        .render(Map.of());
// -> 当前身份：访客

// 严格模式：缺变量 / 多余变量抛异常
PromptTemplate strict = PromptTemplate.builder()
        .template("你好 {name}")
        .strict(true)
        .build();
strict.render(Map.of("name", "王五"));        // OK
// strict.render(Map.of("x", 1));           // 抛 IllegalArgumentException（多余变量）
// strict.render(Map.of());                 // 抛 IllegalArgumentException（缺变量）

// 列出模板中所有占位符名（去重，含带默认值的）
List<String> vars = strict.variables();       // -> ["name"]
```

### 从 classpath 加载模板

把长模板放进资源文件（UTF-8 编码），用 `fromResource` 加载：

```java
PromptTemplate t = PromptTemplate.fromResource("prompts/rag-system.txt");
String system = t.render(Map.of("context", retrievedContext));
```

> 注意：模板文件请保存为 **UTF-8** 编码；资源路径相对于 classpath 根。

## ChatTemplate

多消息对话模板：把一组带占位符的 `ChatMessage` 一次性渲染成消息列表，
并支持 few-shot 示例组装。

```java
import com.sure.ai.model.ChatMessage;
import com.sure.ai.rag.prompt.ChatTemplate;
import java.util.List;
import java.util.Map;

ChatTemplate chat = ChatTemplate.builder()
        .system("你是知识库问答助手，依据下列上下文回答：\n{context}")
        // few-shot 示例对：渲染时插入到系统消息之后、第一条用户消息之前
        .addExample(
                ChatMessage.user("什么是 RAG？"),
                ChatMessage.assistant("RAG 是检索增强生成……"))
        .user("{question}")
        .build();

List<ChatMessage> messages = chat.render(Map.of(
        "context", "……命中文档……",
        "question", "如何投保农险？"));
// 顺序：system -> 示例user -> 示例assistant -> user(真实问题)
```

- 每条模板消息的 `content` 都按 `PromptTemplate` 规则渲染（非严格模式）。
- few-shot 示例本身是**完整消息**，不再做占位符替换。
- 也可用 `ChatTemplate.fromMessages(List<ChatMessage>)` 从已有消息列表构造。

## 与 RagPipeline 集成

`RagPipeline` 的 `systemPromptTemplate` 本就使用 `{context}` 占位符，可直接用
`PromptTemplate` 预渲染后传入 Builder，或把 `PromptTemplate.variables()` 用于
参数校验：

```java
RagPipeline pipeline = RagPipeline.builder()
        .chatClient(client)
        .chatModel("your-model")
        .systemPromptTemplate(
                PromptTemplate.fromString(
                        "你是{domain}客服。仅依据上下文回答：\n{context}")
                        .render(Map.of("domain", "农险")))
        .build();
```

> 说明：`PromptTemplate` / `ChatTemplate` 不会自动注入默认 `RagPipeline` 链路，
> 保持向后兼容；`RagPipeline.java` 未做任何改动。

## 查询改写（Multi-Query Retrieval）

`QueryRewriter` 把原始查询改写为多个语义等价、表述不同的查询，用于多查询检索扩展：
对每个改写查询分别检索，再合并结果去重，召回单一路径遗漏的相关文档。

### 接口

```java
public interface QueryRewriter {
    List<String> rewrite(String query, int count);
}
```

### ModelQueryRewriter

基于对话模型的实现，内置提示词：

```
请将以下用户问题改写为 {count} 个语义等价但表述不同的查询，每行一个，不要编号，不要解释：
{query}
```

- 按行拆分模型输出，去空行，截断到 `count` 条；模型返回不足时返回实际数量，不抛异常。
- **容错**：模型调用异常、无返回文本或解析为空时，回退为单元素列表（仅原查询），
  保证检索链路可继续运行。

```java
import com.sure.ai.rag.rewriter.ModelQueryRewriter;

ModelQueryRewriter rewriter = ModelQueryRewriter.builder()
        .chatClient(client)
        .model("your-model")
        .maxTokens(512)
        .build();

List<String> queries = rewriter.rewrite("如何投保农险？", 3);
```

### 手动接入多查询融合（不修改 RagPipeline）

查询改写不注入默认链路，在检索前手动调用并融合多路结果：

```java
List<String> queries = rewriter.rewrite(originalQuery, 3);

// 对每个改写查询分别检索，合并并按文档 id 去重
Map<String, Document> merged = new LinkedHashMap<>();
for (String q : queries) {
    for (Document doc : retriever.retrieve(q, topK)) {
        merged.putIfAbsent(doc.id(), doc);   // 按 id 去重，保留先出现的高相关结果
    }
}
List<Document> expanded = new ArrayList<>(merged.values());
```

随后把 `expanded` 上下文拼入提示词、调用对话模型生成即可。

## 注意事项

- **严格 vs 非严格**：模板较多且希望尽早暴露漏传变量时用严格模式；
  面向外部输入、希望「能渲染就渲染」时用默认非严格模式。
- **模板文件编码**：`fromResource` 统一按 UTF-8 读取，请保证资源文件编码为 UTF-8。
- **few-shot 位置**：示例对固定插入在系统消息之后、第一条用户消息之前；
  若模板没有 system 消息，则插入在第一条用户消息之前。
- **零网络测试**：`ModelQueryRewriter` 测试使用 mock `AiClient`，全程无真实网络调用。
