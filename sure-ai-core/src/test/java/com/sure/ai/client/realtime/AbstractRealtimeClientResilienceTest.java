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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import com.sure.ai.client.AiConfig;

/**
 * {@link AbstractRealtimeClient} 连接韧性测试：自动重连、退避、心跳超时、生命周期回调。
 *
 * <p>通过注入「同步立即执行」的 {@link RealtimeTaskScheduler}，使重连链在调用线程内确定性跑完，
 * 全程零真实网络。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
public class AbstractRealtimeClientResilienceTest {

	/** 记录全部生命周期/错误事件顺序的监听器。 */
	private static final class RecordingListener implements RealtimeEventListener {

		final List<String> events = Collections.synchronizedList(new ArrayList<>());
		final List<Integer> reconnectAttempts = Collections.synchronizedList(new ArrayList<>());
		final List<Long> reconnectDelays = Collections.synchronizedList(new ArrayList<>());
		int speechStart;
		int speechStop;
		int interrupted;

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
			this.events.add("event:" + type);
		}

		@Override
		public void onConnected() {
			this.events.add("connected");
		}

		@Override
		public void onDisconnected(int code, String reason) {
			this.events.add("disconnected:" + code);
		}

		@Override
		public void onReconnecting(int attempt, long delayMillis) {
			this.events.add("reconnecting:" + attempt);
			this.reconnectAttempts.add(attempt);
			this.reconnectDelays.add(delayMillis);
		}

		@Override
		public void onReconnected(int attempts) {
			this.events.add("reconnected:" + attempts);
		}

		@Override
		public void onReconnectFailed(int attempts) {
			this.events.add("reconnectFailed:" + attempts);
		}

		@Override
		public void onSpeechStart() {
			this.speechStart++;
		}

		@Override
		public void onSpeechStop() {
			this.speechStop++;
		}

		@Override
		public void onInterrupted() {
			this.interrupted++;
		}
	}

	/** Fake WebSocket：记录 ping 次数。 */
	static final class FakeWebSocket implements WebSocket {

		final List<String> sent = new ArrayList<>();
		int pingCount;

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
			this.pingCount++;
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
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

	/** 可脚本化连接器：前 failTimes 次建连抛异常，之后每次返回新 FakeWebSocket。 */
	static final class ScriptedConnector implements RealtimeConnector {

		int connectCount;
		int failTimes;
		final List<FakeWebSocket> sockets = new ArrayList<>();
		final List<URI> uris = new ArrayList<>();
		WebSocket.Listener lastListener;

		@Override
		public WebSocket connect(URI uri, WebSocket.Listener listener) {
			this.uris.add(uri);
			this.connectCount++;
			if (this.failTimes > 0) {
				this.failTimes--;
				throw new IllegalStateException("simulated connect failure");
			}
			FakeWebSocket ws = new FakeWebSocket();
			this.sockets.add(ws);
			this.lastListener = listener;
			listener.onOpen(ws);
			return ws;
		}
	}

	/** 同步调度器：任务在调用线程立即执行，使重连链确定性跑完。 */
	static final class DirectTaskScheduler implements RealtimeTaskScheduler {

		@Override
		public ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
			task.run();
			return null;
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long intervalMillis) {
			return null;
		}

		@Override
		public void shutdown() {
		}
	}

	/** 测试用客户端。 */
	static class TestClient extends AbstractRealtimeClient {

		TestClient(AiConfig config, RealtimeConnector connector, RealtimeEventListener listener,
				RealtimeOptions options) {
			super(config, connector, listener, options);
			this.taskScheduler = new DirectTaskScheduler();
		}

		@Override
		protected URI buildUri() {
			return URI.create("wss://realtime.example.com/v1");
		}

		@Override
		protected void handleMessage(String message) {
		}
	}

	private RecordingListener listener;
	private ScriptedConnector connector;
	private AiConfig config;

	/** 初始化。 */
	@Before
	public void setUp() {
		this.listener = new RecordingListener();
		this.connector = new ScriptedConnector();
		this.config = AiConfig.builder().apiKey("k").build();
	}

	private TestClient client(RealtimeOptions options) {
		return new TestClient(this.config, this.connector, this.listener, options);
	}

	private static RealtimeOptions baseOptions() {
		return RealtimeOptions.builder()
			.autoReconnect(true)
			.maxReconnectAttempts(3)
			.reconnectBaseDelayMillis(1_000)
			.reconnectMaxDelayMillis(10_000)
			.heartbeatIntervalMillis(0) // 关闭固定节拍，测试直接驱动 heartbeatTick
			.build();
	}

	/** 掉线后失败 2 次再成功：生命周期回调顺序与退避正确。 */
	@Test
	public void testReconnectSucceedsAfterFailures() {
		TestClient c = client(baseOptions());
		c.connect(); // 首次成功 → socket[0]
		assertEquals(1, this.connector.sockets.size());
		this.connector.failTimes = 2; // 接下来两次重连失败

		// 模拟对端异常断开
		this.connector.lastListener.onClose(this.connector.sockets.get(0), 1006, "down");

		assertTrue(c.isConnected());
		assertEquals(4, this.connector.connectCount); // 1 次初始 + 3 次重连尝试（前 2 次失败）
		assertEquals(2, this.connector.sockets.size()); // 初始 + 重连成功

		// 回调顺序：初始connected → close → disconnected → 重连ing(1,2失败) → 重连ing(3) → connected → reconnected
		assertEquals(List.of(
			"connected",
			"close", "disconnected:1006",
			"reconnecting:1",
			"error:reconnect failed: simulated connect failure",
			"reconnecting:2",
			"error:reconnect failed: simulated connect failure",
			"reconnecting:3",
			"connected", "reconnected:3"), this.listener.events);
		// 退避：1000, 2000, 4000（指数）
		assertEquals(List.of(1, 2, 3), this.listener.reconnectAttempts);
		assertEquals(List.of(1_000L, 2_000L, 4_000L), this.listener.reconnectDelays);
	}

	/** 超过最大重连次数：回调 onReconnectFailed，不再建连。 */
	@Test
	public void testGiveUpAfterMaxAttempts() {
		TestClient c = client(baseOptions());
		c.connect();
		this.connector.failTimes = 10; // 永远失败
		this.connector.lastListener.onClose(this.connector.sockets.get(0), 1006, "down");

		assertFalse(c.isConnected());
		assertEquals(4, this.connector.connectCount); // 初始1 + 重连尝试3（第4次超过上限不再尝试）
		assertEquals(List.of(1, 2, 3), this.listener.reconnectAttempts);
		assertEquals("reconnectFailed:4",
			this.listener.events.get(this.listener.events.size() - 1));
	}

	/** 关闭自动重连：掉线仅 close+disconnected，不重连。 */
	@Test
	public void testNoReconnectWhenDisabled() {
		RealtimeOptions opts = RealtimeOptions.builder().autoReconnect(false).build();
		TestClient c = client(opts);
		c.connect();
		this.connector.lastListener.onClose(this.connector.sockets.get(0), 1000, "bye");

		assertFalse(c.isConnected());
		assertEquals(1, this.connector.connectCount);
		assertEquals(List.of("connected", "close", "disconnected:1000"), this.listener.events);
	}

	/** 心跳超时：空闲过久 → onError + 掉线 + 自动重连成功。 */
	@Test
	public void testHeartbeatTimeoutTriggersReconnect() {
		TestClient c = client(baseOptions());
		c.connect();
		// 模拟 60s 无入站活动（超过默认/选项超时 10s）
		c.lastActivityNanos = System.nanoTime() - TimeUnit.SECONDS.toNanos(60);

		c.heartbeatTick();

		assertTrue(c.isConnected());
		assertEquals(2, this.connector.sockets.size());
		assertTrue(this.listener.events.get(1).startsWith("error:heartbeat timeout"));
		assertTrue(this.listener.events.contains("disconnected:1006"));
		assertTrue(this.listener.events.contains("reconnected:1"));
	}

	/** 心跳存活：活动新鲜 → 发送 ping，不断线。 */
	@Test
	public void testHeartbeatPingWhenAlive() {
		TestClient c = client(baseOptions());
		c.connect();
		FakeWebSocket ws = this.connector.sockets.get(0);
		c.lastActivityNanos = System.nanoTime(); // 刚活动
		c.heartbeatTick();

		assertEquals(1, ws.pingCount);
		assertTrue(c.isConnected());
		assertFalse(this.listener.events.contains("disconnected:1006"));
	}

	/** 重连成功后会话级 hook 被调用。 */
	@Test
	public void testOnReconnectedSessionHook() {
		final boolean[] hookCalled = { false };
		TestClient c = new TestClient(this.config, this.connector, this.listener, baseOptions()) {
			@Override
			protected void onReconnectedSession() {
				hookCalled[0] = true;
			}
		};
		c.connect();
		this.connector.lastListener.onClose(this.connector.sockets.get(0), 1006, "down");
		assertTrue(hookCalled[0]);
	}

	/** 指数退避封顶。 */
	@Test
	public void testBackoffCapped() {
		RealtimeOptions o = RealtimeOptions.builder()
			.reconnectBaseDelayMillis(1_000)
			.reconnectMaxDelayMillis(3_000)
			.build();
		assertEquals(1_000L, o.backoffMillis(1));
		assertEquals(2_000L, o.backoffMillis(2));
		assertEquals(3_000L, o.backoffMillis(3)); // 封顶
		assertEquals(3_000L, o.backoffMillis(10));
	}

	/** 用户主动 close 后不再重连。 */
	@Test
	public void testUserCloseNoReconnect() {
		TestClient c = client(baseOptions());
		c.connect();
		c.close();
		// 对端随后 echo close
		this.connector.lastListener.onClose(this.connector.sockets.get(0), 1000, "bye");
		assertFalse(c.isConnected());
		// close 之后不应再出现重连
		assertFalse(this.listener.events.contains("reconnecting:1"));
	}
}
