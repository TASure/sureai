/*
 * Copyright (c) 2026 sureai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sure.ai.client.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.Role;
import com.sure.ai.exception.AiException;

/**
 * 对话异步层（接口 default 方法 + {@link AsyncAiClient} 装饰器）单元测试（1.9.0）。
 *
 * <p>使用内存 Fake 客户端，零真实网络。覆盖：default chatAsync 与同步一致、虚拟线程执行、
 * 流式收集与流式异常、异常原样传播、自定义执行器注入、取消中断、装饰器委托与 close。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class AsyncChatClientTest {

	/**  canned 响应：首条候选文本为 "hello-async"。 */
	private static ChatResponse cannedResponse() {
		Choice c = Choice.of(0, com.sure.ai.model.ChatMessage.assistant("hello-async"), "stop");
		return ChatResponse.of("id-1", "m1", List.of(c), null, "{}");
	}

	/** 测试用请求。 */
	private static ChatRequest request() {
		return ChatRequest.builder()
			.model("m1")
			.messages(List.of(com.sure.ai.model.ChatMessage.user("hi")))
			.build();
	}

	/**
	 * 内存 Fake：可配置返回值/异常，记录执行线程、是否被关闭。
	 */
	static final class FakeAiClient implements AiClient {

		final AtomicReference<Boolean> virtual = new AtomicReference<>(Boolean.FALSE);
		final AtomicReference<String> threadName = new AtomicReference<>("");
		final AtomicBoolean closed = new AtomicBoolean(false);
		/** chat 抛此异常（非 null 时）。 */
		RuntimeException chatError;
		/** chatStream 抛此异常（非 null 时，在投递分片后）。 */
		RuntimeException streamError;

		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			virtual.set(Thread.currentThread().isVirtual());
			threadName.set(Thread.currentThread().getName());
			if (chatError != null) {
				throw chatError;
			}
			return cannedResponse();
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
			consumer.accept(chunk("a"));
			consumer.accept(chunk("b"));
			consumer.accept(chunk("c"));
			if (streamError != null) {
				throw streamError;
			}
		}

		@Override
		public void close() {
			closed.set(true);
		}
	}

	private static ChatStreamChunk chunk(String delta) {
		return ChatStreamChunk.of("id", Role.ASSISTANT, delta, List.of(), null);
	}

	/** default chatAsync 返回结果与同步 chat 完全一致。 */
	@Test
	public void defaultChatAsyncMatchesSync() throws Exception {
		FakeAiClient fake = new FakeAiClient();
		ChatResponse sync = fake.chat(request());
		ChatResponse async = fake.chatAsync(request()).get(5, TimeUnit.SECONDS);
		assertEquals(sync.firstText(), async.firstText());
		assertEquals("hello-async", async.firstText());
	}

	/** default chatAsync 在虚拟线程上执行。 */
	@Test
	public void defaultChatAsyncRunsOnVirtualThread() throws Exception {
		FakeAiClient fake = new FakeAiClient();
		fake.chatAsync(request()).get(5, TimeUnit.SECONDS);
		assertTrue(fake.virtual.get());
	}

	/** 流式异步：收集全部分片后未来以 null 完成。 */
	@Test
	public void chatStreamAsyncCollectsAllChunksThenCompletes() throws Exception {
		FakeAiClient fake = new FakeAiClient();
		StringBuilder collected = new StringBuilder();
		CompletableFuture<Void> f = fake.chatStreamAsync(request(),
			chunk -> collected.append(chunk.deltaText()));
		assertNull(f.get(5, TimeUnit.SECONDS));
		assertEquals("abc", collected.toString());
	}

	/** 流式异步：流过程中异常时未来异常完成，cause 为原异常。 */
	@Test
	public void chatStreamAsyncExceptionallyOnError() {
		FakeAiClient fake = new FakeAiClient();
		AiException boom = new AiException("stream-down");
		fake.streamError = boom;
		CompletableFuture<Void> f = fake.chatStreamAsync(request(), chunk -> {
		});
		ExecutionException ee = assertThrows(ExecutionException.class,
			() -> f.get(5, TimeUnit.SECONDS));
		assertSame(boom, ee.getCause());
	}

	/** chatAsync 异常原样传播：cause 即底层 AiException。 */
	@Test
	public void chatAsyncExceptionPropagation() {
		FakeAiClient fake = new FakeAiClient();
		AiException boom = new AiException("boom");
		fake.chatError = boom;
		CompletableFuture<ChatResponse> f = fake.chatAsync(request());
		ExecutionException ee = assertThrows(ExecutionException.class,
			() -> f.get(5, TimeUnit.SECONDS));
		assertSame(boom, ee.getCause());
	}

	/** 装饰器注入自定义单线程池：任务确实跑在自定义池（平台线程），而非虚拟线程。 */
	@Test
	public void decoratorUsesInjectedExecutor() throws Exception {
		FakeAiClient fake = new FakeAiClient();
		ExecutorService custom = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "custom-single-1");
			return t;
		});
		try {
			ChatResponse r = new AsyncAiClient(fake, custom).chatAsync(request())
				.get(5, TimeUnit.SECONDS);
			assertEquals("hello-async", r.firstText());
			assertEquals("custom-single-1", fake.threadName.get());
			assertFalse("自定义池是平台线程", fake.virtual.get());
		} finally {
			custom.shutdownNow();
		}
	}

	/** 装饰器同步方法正确委托：name/chat/chatStream/close。 */
	@Test
	public void decoratorDelegatesSyncMethodsAndClose() throws Exception {
		FakeAiClient fake = new FakeAiClient();
		AsyncAiClient async = new AsyncAiClient(fake);
		assertEquals("fake", async.name());
		assertEquals("hello-async", async.chat(request()).firstText());
		StringBuilder sb = new StringBuilder();
		async.chatStream(request(), c -> sb.append(c.deltaText()));
		assertEquals("abc", sb.toString());
		assertFalse(fake.closed.get());
		async.close();
		assertTrue(fake.closed.get());
	}

	/** 装饰后同步行为与裸 client 一致（零回归）。 */
	@Test
	public void syncBehaviorUnchangedAfterWrap() {
		FakeAiClient bare = new FakeAiClient();
		FakeAiClient wrappedSource = new FakeAiClient();
		AsyncAiClient wrapped = new AsyncAiClient(wrappedSource);
		assertEquals(bare.chat(request()).firstText(), wrapped.chat(request()).firstText());
		assertEquals(bare.name(), wrapped.name());
	}

	/** AsyncClients 工厂一行包装可用。 */
	@Test
	public void factoryWrapChatWorks() throws Exception {
		FakeAiClient fake = new FakeAiClient();
		ChatResponse r = AsyncClients.chat(fake).chatAsync(request()).get(5, TimeUnit.SECONDS);
		assertNotNull(r);
		assertEquals("hello-async", r.firstText());
	}
}
