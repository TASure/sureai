# 全链路异步 / 虚拟线程（Async & Virtual Threads）

> 自 **v1.9.0** 起，sureai 在 `sure-ai-core` 的 `com.sure.ai.client.async` 包提供一套**可注入执行器**的全量异步方法族，把所有阻塞式 AI 请求 IO 承载在 JDK 21 虚拟线程上，对既有同步 API 完全向后兼容。

## 执行模型

所有异步任务统一跑在 **Java 21 虚拟线程**上：进程级共享一个 `Executors.newThreadPerTaskExecutor(...)` 虚拟线程工厂执行器，每个异步任务占用一条独立虚拟线程，命名前缀 `sureai-vt-`，均为守护线程（不阻止 JVM 退出）。虚拟线程对阻塞 IO（HTTP 等待、SSE 流式轮询、视频/图像任务轮询）天然友好——阻塞时自动让出载体平台线程。

- 共享执行器随进程存活，**不随任何 `Async*Client` 实例关闭而 shutdown**；装饰器的 `close()` 仅委托底层业务客户端释放连接。
- 需要受控生命周期的调用方，请通过带 `Executor` 的构造器注入自建执行器，并自行管理其关闭。
- `future.cancel(true)` 会中断工作虚拟线程（阻塞 IO 以 `InterruptedException` 解除阻塞）；任务抛出的异常**原样**进入异常完成态（`get()` 时包在 `ExecutionException` 的 cause 中，不做二次包装）。

## 方式一：`AiClient` default 异步方法族（开箱即用）

任意平台 `XxxClient` 本身就带 default 异步方法（走共享虚拟线程执行器），无需额外包装：

```java
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.model.ChatRequest;

OpenAiClient client = OpenAiClient.builder().apiKey("sk-xxx").build();

// 异步对话，返回 CompletableFuture<ChatResponse>
client.chatAsync(ChatRequest.builder()
        .model("gpt-4o-mini")
        .messages(List.of(ChatMessage.user("你好")))
        .build())
    .thenAccept(resp -> System.out.println(resp.firstText()));

// 异步流式对话，流结束时 future 以 null 完成
client.chatStreamAsync(req, chunk -> {
        if (chunk.deltaText() != null) System.out.print(chunk.deltaText());
    })
    .exceptionally(ex -> { ex.printStackTrace(); return null; });
```

| 方法 | 返回 |
|------|------|
| `AiClient.chatAsync(ChatRequest)` | `CompletableFuture<ChatResponse>` |
| `AiClient.chatStreamAsync(ChatRequest, Consumer<ChatStreamChunk>)` | `CompletableFuture<Void>`（流结束完成） |

## 方式二：`AsyncClients` 一行包装（自定义执行器 / 类型明确）

`com.sure.ai.client.async.AsyncClients` 是统一包装工厂：一行把任意同步 Client 包装为对应的异步装饰器。无参 `wrap` 方法默认使用共享虚拟线程执行器；带 `Executor` 重载用于注入受控线程池。

```java
import com.sure.ai.client.async.AsyncClients;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

OpenAiClient client = OpenAiClient.builder().apiKey("sk-xxx").build();

// 默认：共享虚拟线程执行器
AsyncAiClient ai = AsyncClients.chat(client);
CompletableFuture<ChatResponse> f = ai.chatAsync(req);

// 自定义受控执行器（限流 / 隔离 / 生命周期管理）
ExecutorService pool = Executors.newFixedThreadPool(4);
AsyncAiClient controlled = AsyncClients.chat(client, pool);
```

| 工厂方法 | 包装为 | 异步方法 |
|----------|--------|----------|
| `AsyncClients.chat(AiClient[, Executor])` | `AsyncAiClient` | `chatAsync` / `chatStreamAsync` |
| `AsyncClients.embed(EmbeddingClient[, Executor])` | `AsyncEmbeddingClient` | `embedAsync(EmbeddingRequest)` / `embedAsync(String model, String text)` |
| `AsyncClients.image(ImageClient[, Executor])` | `AsyncImageClient` | `generateAsync(ImageRequest)` / `generateAsync(String model, String prompt)` |
| `AsyncClients.video(VideoClient[, Executor])` | `AsyncVideoClient` | `generateAsync(VideoRequest)` |
| `AsyncClients.audio(AudioClient[, Executor])` | `AsyncAudioClient` | `synthesizeAsync(...)` / `transcribeAsync(...)` |

示例：

```java
// 异步向量嵌入
AsyncEmbeddingClient emb = AsyncClients.embed(client);
emb.embedAsync("text-embedding-3-small", "测试文本")
   .thenAccept(r -> System.out.println("dim=" + r.embeddings().get(0).length));

// 异步图像生成
AsyncImageClient img = AsyncClients.image(imageClient);
img.generateAsync(OpenAiModels.DALL_E_3, "一只猫咪")
   .thenAccept(r -> System.out.println(r.firstUrl()));
```

## 底层工具 `AsyncExecutors`

`com.sure.ai.client.async.AsyncExecutors` 提供执行器与任务原语：

```java
// 取共享虚拟线程 per-task 执行器（单例，勿 shutdown）
ExecutorService vt = AsyncExecutors.virtualThreadExecutor();

// 在给定执行器上异步执行，返回可取消 future（取消即中断工作线程）
CompletableFuture<String> f = AsyncExecutors.supplyAsync(
        () -> client.chat(req).firstText(), vt);
CompletableFuture<Void> done = AsyncExecutors.runAsync(
        () -> client.chatStream(req, chunk -> {}), vt);
```

## 线程安全与关闭语义

- 装饰器除 `close()` 外均为无状态委托，底层平台 Client 自身线程安全。
- 装饰器的 `close()` 仅委托底层客户端，**不关闭**本类使用的执行器。
- 同步方法与 `close()` 原样委托底层 Client——异步能力是叠加层，不改变任何同步行为。
