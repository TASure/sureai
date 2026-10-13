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
package com.sure.ai.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Role;

/**
 * {@link GatewayTimeoutDecorator} 单元测试（零真实网络：慢调用仅在假 client 内部 sleep）。
 *
 * @author sureai
 * @since 2.7.0
 */
public class GatewayTimeoutDecoratorTest {

	/** 构造一个最简请求。 */
	private static ChatRequest req() {
		return ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.build();
	}

	/**
	 * 正常调用未超时：返回 delegate 结果且仅调用一次。
	 */
	@Test
	public void testChatSuccessWithinTimeout() {
		FakeClient delegate = new FakeClient("openai");
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 5_000);
		try {
			ChatResponse resp = d.chat(req());
			assertEquals("openai:ok", resp.firstText());
			assertEquals(1, delegate.chatCalls);
		} finally {
			d.close();
		}
	}

	/**
	 * name() 返回 "timeout:" 前缀。
	 */
	@Test
	public void testNamePrefix() {
		FakeClient delegate = new FakeClient("openai");
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 5_000);
		try {
			assertEquals("timeout:openai", d.name());
		} finally {
			d.close();
		}
	}

	/**
	 * 超时路径：慢 client sleep 300ms 而超时 50ms → 抛 AiTimeoutException，message 含 "timeout" 与模型名。
	 */
	@Test
	public void testChatTimeoutThrowsAiTimeout() {
		SlowClient delegate = new SlowClient("slow", 300L, List.of());
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 50L);
		try {
			d.chat(req());
			fail("expected AiTimeoutException");
		} catch (AiTimeoutException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("timeout"));
			assertTrue(ex.getMessage(), ex.getMessage().contains("gpt-4o"));
		} finally {
			d.close();
		}
	}

	/**
	 * per-request 覆盖：extra 给更大超时（5000ms）超过慢调用耗时（100ms）→ 不超时成功。
	 */
	@Test
	public void testPerRequestOverrideLargerTimeoutSucceeds() {
		SlowClient delegate = new SlowClient("slow", 100L, List.of());
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 50L);
		ChatRequest request = ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.extra(GatewayTimeoutDecorator.EXTRA_TIMEOUT_MS, 5_000)
			.build();
		try {
			assertEquals("slow:ok", d.chat(request).firstText());
			assertEquals(1, delegate.chatCalls);
		} finally {
			d.close();
		}
	}

	/**
	 * per-request 非法值（负数）回退构造值 50ms → 仍超时抛 AiTimeoutException。
	 */
	@Test
	public void testPerRequestNegativeFallsBackToDefault() {
		SlowClient delegate = new SlowClient("slow", 300L, List.of());
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 50L);
		ChatRequest request = ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.extra(GatewayTimeoutDecorator.EXTRA_TIMEOUT_MS, -5)
			.build();
		try {
			d.chat(request);
			fail("expected AiTimeoutException");
		} catch (AiTimeoutException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("timeout"));
		} finally {
			d.close();
		}
	}

	/**
	 * per-request 非法值（非 Number 字符串）回退构造值 50ms → 仍超时抛 AiTimeoutException。
	 */
	@Test
	public void testPerRequestNonNumberFallsBackToDefault() {
		SlowClient delegate = new SlowClient("slow", 300L, List.of());
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 50L);
		ChatRequest request = ChatRequest.builder()
			.model("gpt-4o")
			.messages(List.of(ChatMessage.user("hi")))
			.extra(GatewayTimeoutDecorator.EXTRA_TIMEOUT_MS, "soon")
			.build();
		try {
			d.chat(request);
			fail("expected AiTimeoutException");
		} catch (AiTimeoutException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("timeout"));
		} finally {
			d.close();
		}
	}

	/**
	 * delegate 抛鉴权异常：原样透传（同类型同实例），不误判超时。
	 */
	@Test
	public void testDelegateAuthExceptionPassthrough() {
		AiAuthException biz = new AiAuthException("bad key", "{}");
		FakeClient delegate = new FakeClient("openai").throwOn(biz);
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 5_000);
		try {
			d.chat(req());
			fail("expected AiAuthException");
		} catch (AiAuthException ex) {
			assertEquals(biz, ex);
		} finally {
			d.close();
		}
	}

	/**
	 * delegate 抛普通运行时异常：原样透传，类型不变。
	 */
	@Test
	public void testDelegateRuntimeExceptionPassthrough() {
		IllegalStateException boom = new IllegalStateException("boom");
		FakeClient delegate = new FakeClient("openai").throwOn(boom);
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 5_000);
		try {
			d.chat(req());
			fail("expected IllegalStateException");
		} catch (IllegalStateException ex) {
			assertEquals(boom, ex);
		} finally {
			d.close();
		}
	}

	/**
	 * 流式未超时：全部分片按序透传。
	 */
	@Test
	public void testStreamSuccessCollectsAllChunks() {
		FakeClient delegate = new FakeClient("openai").stream("hello", "world");
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 5_000);
		List<String> out = new ArrayList<>();
		try {
			d.chatStream(req(), chunk -> out.add(chunk.deltaText()));
			assertEquals(List.of("hello", "world"), out);
		} finally {
			d.close();
		}
	}

	/**
	 * 流式超时：慢流（sleep 300ms 后才投递）在 50ms 整体超时 → 抛 AiTimeoutException，
	 * 且 consumer 因丢弃标志未收到任何分片。
	 */
	@Test
	public void testStreamTimeoutThrowsAiTimeout() {
		SlowClient delegate = new SlowClient("slow", 300L, List.of("a", "b"));
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 50L);
		List<String> out = new ArrayList<>();
		try {
			d.chatStream(req(), chunk -> out.add(chunk.deltaText()));
			fail("expected AiTimeoutException");
		} catch (AiTimeoutException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("timeout"));
			assertTrue("超时后不应收到分片", out.isEmpty());
		} finally {
			d.close();
		}
	}

	/**
	 * 调用方线程被中断：恢复 interrupt 标志并抛 AiException（不触碰 worker 线程之外的资源）。
	 */
	@Test
	public void testInterruptedWaitingRaisesAiException() throws Exception {
		SlowClient delegate = new SlowClient("slow", 60_000L, List.of());
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 60_000L);
		AtomicReference<Throwable> caught = new AtomicReference<>();
		Thread holder = new Thread(() -> {
			try {
				d.chat(req());
			} catch (Throwable t) {
				caught.set(t);
			}
		}, "junit-holder");
		holder.start();
		holder.interrupt();
		holder.join();
		try {
			assertTrue(caught.get() instanceof AiException);
			assertTrue(caught.get().getMessage(), caught.get().getMessage().contains("interrupted"));
		} finally {
			d.close();
		}
	}

	/**
	 * close 后再调用 chat：抛 closed 异常不阻塞；close 幂等可重复调用。
	 */
	@Test
	public void testCloseShutsExecutor() {
		FakeClient delegate = new FakeClient("openai");
		GatewayTimeoutDecorator d = new GatewayTimeoutDecorator(delegate, 5_000);
		d.close();
		d.close();
		assertTrue(delegate.closed);
		try {
			d.chat(req());
			fail("expected AiException after close");
		} catch (AiException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("closed"));
		}
	}

	/**
	 * 构造校验：timeoutMs 为 0 或负数抛 IllegalArgumentException。
	 */
	@Test
	public void testRejectNonPositiveTimeoutMs() {
		FakeClient delegate = new FakeClient("openai");
		try {
			new GatewayTimeoutDecorator(delegate, 0L);
			fail("expected IllegalArgumentException for 0");
		} catch (IllegalArgumentException ex) {
			assertTrue(ex.getMessage().contains("positive"));
		}
		try {
			new GatewayTimeoutDecorator(delegate, -100L);
			fail("expected IllegalArgumentException for -100");
		} catch (IllegalArgumentException ex) {
			assertTrue(ex.getMessage().contains("positive"));
		}
	}

	/**
	 * 构造校验：delegate 为 null 抛 NullPointerException。
	 */
	@Test
	public void testRejectNullDelegate() {
		try {
			new GatewayTimeoutDecorator(null, 1_000L);
			fail("expected NullPointerException");
		} catch (NullPointerException ex) {
			assertTrue(ex.getMessage().contains("delegate"));
		}
	}

	/** 慢假 client：chat/chatStream 先 sleep（仅在 worker 线程内部模拟慢调用）再返回。 */
	private static final class SlowClient implements AiClient {

		/** client 名。 */
		private final String name;

		/** worker 线程内 sleep 毫秒数。 */
		private final long sleepMs;

		/** chatStream 投递的分片文本。 */
		private final List<String> deltas;

		/** chat 调用计数。 */
		int chatCalls;

		SlowClient(String name, long sleepMs, List<String> deltas) {
			this.name = name;
			this.sleepMs = sleepMs;
			this.deltas = deltas;
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.chatCalls++;
			sleepQuietly();
			return FakeClient.response("slow:ok");
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
			sleepQuietly();
			for (String d : this.deltas) {
				consumer.accept(ChatStreamChunk.of("chunk", Role.ASSISTANT, d, null, null));
			}
		}

		@Override
		public void close() {
		}

		/** worker 线程内的可中断 sleep；被 cancel(true) 打断时恢复中断标志并退出。 */
		private void sleepQuietly() {
			try {
				Thread.sleep(this.sleepMs);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new AiException("slow client interrupted");
			}
		}
	}
}
