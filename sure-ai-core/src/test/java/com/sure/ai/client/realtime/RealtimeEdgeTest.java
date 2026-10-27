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

package com.sure.ai.client.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;

/**
 * {@link AbstractRealtimeClient} 内部监听器与关闭分支、{@link RealtimeEventListener}
 * 默认空方法、{@link DefaultRealtimeConnector} 关闭/建连分支测试。
 *
 * @author sureai
 * @since 1.4.0
 */
public class RealtimeEdgeTest {

	/** 记录所有事件的监听器。 */
	private static final class Recording implements RealtimeEventListener {
		final List<String> events = new ArrayList<>();

		@Override
		public void onConnected() {
			this.events.add("connected");
		}

		@Override
		public void onDisconnected(int code, String reason) {
			this.events.add("disconnected:" + code);
		}

		@Override
		public void onTranscript(String text) {
		}

		@Override
		public void onAudio(byte[] audio) {
		}

		@Override
		public void onError(String error) {
			this.events.add("error:" + error);
		}

		@Override
		public void onClose() {
			this.events.add("close");
		}

		@Override
		public void onEvent(String type, String rawJson) {
		}
	}

	/** 可配置行为的 fake WebSocket。 */
	private static final class FakeWs implements WebSocket {
		boolean throwOnPing;
		boolean throwOnClose;
		final List<String> sent = new ArrayList<>();

		@Override
		public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
			this.sent.add(data.toString());
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
			if (this.throwOnPing) {
				throw new RuntimeException("ping boom");
			}
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
			if (this.throwOnClose) {
				throw new RuntimeException("close boom");
			}
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public void request(long n) {
		}

		@Override
		public String getSubprotocol() {
			return null;
		}

		@Override
		public boolean isInputClosed() {
			return false;
		}

		@Override
		public boolean isOutputClosed() {
			return false;
		}

		@Override
		public void abort() {
		}
	}

	/** 脚本化连接器。 */
	private static class Scripted implements RealtimeConnector {
		final FakeWs ws = new FakeWs();
		WebSocket.Listener listener;
		int connectCalls;

		@Override
		public WebSocket connect(URI uri, WebSocket.Listener listener) {
			this.connectCalls++;
			this.listener = listener;
			listener.onOpen(this.ws);
			return this.ws;
		}
	}

	/** 可关闭连接器（实现 AutoCloseable）。 */
	private static final class ClosingConnector extends Scripted implements AutoCloseable {
		final AtomicBoolean closed = new AtomicBoolean();
		boolean throwOnClose;

		@Override
		public void close() throws Exception {
			this.closed.set(true);
			if (this.throwOnClose) {
				throw new IllegalStateException("connector close boom");
			}
		}
	}

	private static AiConfig config() {
		return AiConfig.builder().apiKey("k").build();
	}

	/** 测试客户端：可选传入 null options 触发默认值分支。 */
	private static final class TestClient extends AbstractRealtimeClient {
		final boolean throwOnMessage;

		TestClient(AiConfig cfg, RealtimeConnector c, RealtimeEventListener l, RealtimeOptions o,
				boolean throwOnMessage) {
			super(cfg, c, l, o);
			this.throwOnMessage = throwOnMessage;
			this.taskScheduler = new AbstractRealtimeClientResilienceTest.DirectTaskScheduler();
		}

		@Override
		protected URI buildUri() {
			return URI.create("wss://realtime.example.com/v1");
		}

		@Override
		protected void handleMessage(String message) {
			if (this.throwOnMessage) {
				throw new RuntimeException("handle boom");
			}
		}
	}

	/** options 为 null 时归一为默认值。 */
	@Test
	public void testNullOptionsDefaults() {
		Scripted c = new Scripted();
		Recording l = new Recording();
		TestClient client = new TestClient(config(), c, l, null, false);
		client.connect();
		assertTrue(client.isConnected());
		assertEquals(1, c.connectCalls);
	}

	/** 用户关闭后 heartbeatTick 直接返回。 */
	@Test
	public void testHeartbeatTickAfterClose() {
		Scripted c = new Scripted();
		Recording l = new Recording();
		TestClient client = new TestClient(config(), c, l, RealtimeOptions.builder().build(), false);
		client.connect();
		client.close();
		// 不抛异常即覆盖 userClosed 早退分支
		client.heartbeatTick();
	}

	/** sendPing 抛异常被吞掉。 */
	@Test
	public void testSendPingExceptionSwallowed() {
		Scripted c = new Scripted();
		Recording l = new Recording();
		TestClient client = new TestClient(config(), c, l, RealtimeOptions.builder().build(), false);
		client.connect();
		c.ws.throwOnPing = true;
		client.lastActivityNanos = System.nanoTime();
		client.heartbeatTick();
		assertTrue(client.isConnected());
	}

	/** close 时 sendClose 异常被隔离并回调 onError。 */
	@Test
	public void testCloseSendCloseException() {
		Scripted c = new Scripted();
		Recording l = new Recording();
		TestClient client = new TestClient(config(), c, l, RealtimeOptions.builder().build(), false);
		client.connect();
		c.ws.throwOnClose = true;
		client.close();
		assertTrue(l.events.contains("error:close failed: close boom"));
	}

	/** 连接器为 AutoCloseable 时被关闭。 */
	@Test
	public void testConnectorAutoCloseable() {
		ClosingConnector c = new ClosingConnector();
		Recording l = new Recording();
		TestClient client = new TestClient(config(), c, l, RealtimeOptions.builder().build(), false);
		client.connect();
		client.close();
		assertTrue(c.closed.get());
	}

	/** 连接器 close 抛异常被隔离。 */
	@Test
	public void testConnectorCloseExceptionIsolated() {
		ClosingConnector c = new ClosingConnector();
		c.throwOnClose = true;
		Recording l = new Recording();
		TestClient client = new TestClient(config(), c, l, RealtimeOptions.builder().build(), false);
		client.connect();
		client.close();
		assertTrue(l.events.stream().anyMatch(e -> e.startsWith("error:connector close failed")));
	}

	/** onText 触发 handleMessage 异常被隔离。 */
	@Test
	public void testOnTextHandleException() {
		Scripted c = new Scripted();
		Recording l = new Recording();
		TestClient client = new TestClient(config(), c, l, RealtimeOptions.builder().build(), true);
		client.connect();
		c.listener.onText(c.ws, "hello", true);
		assertTrue(l.events.contains("error:handle message failed: handle boom"));
	}

	/** onPong 更新活动时间并请求下一帧。 */
	@Test
	public void testOnPong() {
		Scripted c = new Scripted();
		Recording l = new Recording();
		TestClient client = new TestClient(config(), c, l, RealtimeOptions.builder().build(), false);
		client.connect();
		c.listener.onPong(c.ws, ByteBuffer.allocate(0));
		// 不抛异常即覆盖
		assertTrue(client.isConnected());
	}

	/** 传输层 onError 回调并触发掉线。 */
	@Test
	public void testTransportOnError() {
		Scripted c = new Scripted();
		Recording l = new Recording();
		RealtimeOptions opts = RealtimeOptions.builder().autoReconnect(false).build();
		TestClient client = new TestClient(config(), c, l, opts, false);
		client.connect();
		c.listener.onError(c.ws, new RuntimeException("boom"));
		assertFalse(client.isConnected());
		assertTrue(l.events.stream().anyMatch(e -> e.startsWith("error:boom")));
		assertTrue(l.events.contains("disconnected:1006"));
	}

	/** RealtimeEventListener 默认空方法可被调用。 */
	@Test
	public void testEventListenerDefaultMethods() {
		RealtimeEventListener l = new RealtimeEventListener() {
			@Override
			public void onConnected() {
			}

			@Override
			public void onDisconnected(int code, String reason) {
			}

			@Override
			public void onTranscript(String text) {
			}

			@Override
			public void onAudio(byte[] audio) {
			}

			@Override
			public void onError(String error) {
			}

			@Override
			public void onClose() {
			}

			@Override
			public void onEvent(String type, String rawJson) {
			}
		};
		l.onReconnectFailed(3);
		l.onSpeechStart();
		l.onSpeechStop();
		l.onInterrupted();
		// 无异常即覆盖默认空体
	}

	/** DefaultRealtimeConnector：关闭后建连抛异常；close 幂等。 */
	@Test
	public void testDefaultConnectorClosedAndConnect() {
		DefaultRealtimeConnector connector = new DefaultRealtimeConnector(java.util.Map.of());
		connector.close();
		try {
			connector.connect(URI.create("ws://127.0.0.1:1/nope"), new NoopWsListener());
			org.junit.Assert.fail("应抛出 AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("closed"));
		}
	}

	/** 无操作 WebSocket 监听器。 */
	private static final class NoopWsListener implements WebSocket.Listener {
		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			return null;
		}
	}

	/** RealtimeOptions builder 边界值与访问器。 */
	@Test
	public void testRealtimeOptionsBounds() {
		RealtimeOptions o = RealtimeOptions.builder()
			.heartbeatIntervalMillis(-1)
			.heartbeatTimeoutMillis(-1)
			.reconnectBaseDelayMillis(-2)
			.reconnectMaxDelayMillis(-3)
			.maxReconnectAttempts(-5)
			.build();
		// maxReconnectAttempts 经 Math.max(0,..) 归零
		assertEquals(0, o.maxReconnectAttempts());
		// base/max delay 经 Math.max(0,..) 归零
		assertEquals(0L, o.reconnectBaseDelayMillis());
		assertEquals(0L, o.reconnectMaxDelayMillis());
		// 心跳间隔/超时原样存取
		assertEquals(-1L, o.heartbeatIntervalMillis());
		assertEquals(-1L, o.heartbeatTimeoutMillis());
		// backoff 在 attempt=0 时直接返回 min(base,max)
		assertEquals(0L, o.backoffMillis(0));
	}
}
