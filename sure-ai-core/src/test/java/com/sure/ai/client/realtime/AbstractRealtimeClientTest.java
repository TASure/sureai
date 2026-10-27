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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.Before;
import org.junit.Test;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link AbstractRealtimeClient} 测试：FakeConnector + FakeWebSocket，零真实网络。
 *
 * @author sureai
 * @since 0.2.0
 */
public class AbstractRealtimeClientTest {

	/** 内存事件收集器。 */
	private static final class Collector implements RealtimeEventListener {

		String transcript;
		byte[] audio;
		String error;
		int closeCount;
		String eventType;
		String eventRaw;

		@Override
		public void onTranscript(String text) {
			this.transcript = text;
		}

		@Override
		public void onAudio(byte[] audio) {
			this.audio = audio;
		}

		@Override
		public void onError(String error) {
			this.error = error;
		}

		@Override
		public void onClose() {
			this.closeCount++;
		}

		@Override
		public void onEvent(String type, String rawJson) {
			this.eventType = type;
			this.eventRaw = rawJson;
		}
	}

	/** Fake WebSocket：记录发送文本与关闭标志。 */
	static final class FakeWebSocket implements WebSocket {

		final List<String> sent = new java.util.ArrayList<>();
		boolean closed;
		int closeCode = -1;

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
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
			this.closed = true;
			this.closeCode = statusCode;
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
			return this.closed;
		}

		@Override
		public boolean isOutputClosed() {
			return this.closed;
		}

		@Override
		public void abort() {
			this.closed = true;
		}
	}

	/** Fake 连接器：捕获 listener 并返回 FakeWebSocket。 */
	static final class FakeConnector implements RealtimeConnector {

		FakeWebSocket socket = new FakeWebSocket();
		WebSocket.Listener listener;
		URI uri;

		@Override
		public WebSocket connect(URI uri, WebSocket.Listener listener) {
			this.uri = uri;
			this.listener = listener;
			this.listener.onOpen(this.socket);
			return this.socket;
		}
	}

	/** 测试用具体客户端：固定 URI，解析常见 Realtime 事件。 */
	static final class TestRealtimeClient extends AbstractRealtimeClient {

		TestRealtimeClient(AiConfig config, RealtimeConnector connector,
				RealtimeEventListener listener) {
			super(config, connector, listener);
		}

		@Override
		protected URI buildUri() {
			return URI.create("wss://realtime.example.com/v1?model=gpt-realtime");
		}

		@Override
		protected void handleMessage(String message) {
			JsonObject o = Json.parse(message).getAsJsonObject();
			String type = o.optString("type", "unknown");
			switch (type) {
				case "conversation.item.input_audio_transcription.completed":
					this.eventListener.onTranscript(o.optString("transcript", ""));
					break;
				case "response.audio.delta":
					String b64 = o.optString("delta", "");
					this.eventListener.onAudio(Base64.getDecoder().decode(b64));
					break;
				case "error":
					this.eventListener.onError(o.optString("error", "unknown"));
					break;
				default:
					this.eventListener.onEvent(type, message);
			}
		}
	}

	private Collector collector;
	private FakeConnector connector;
	private TestRealtimeClient client;

	/** 初始化。 */
	@Before
	public void setUp() {
		this.collector = new Collector();
		this.connector = new FakeConnector();
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl("https://api.example.com").build();
		this.client = new TestRealtimeClient(cfg, this.connector, this.collector);
	}

	/** buildUri 构造正确。 */
	@Test
	public void testBuildUri() {
		this.client.connect();
		assertEquals("wss://realtime.example.com/v1?model=gpt-realtime", this.connector.uri.toString());
		assertTrue(this.client.isConnected());
	}

	/** sendText 经 WebSocket 发出。 */
	@Test
	public void testSendText() {
		this.client.connect();
		this.client.sendText("hello");
		assertEquals("hello", this.connector.socket.sent.get(0));
	}

	/** sendAudio 自动 base64 编码。 */
	@Test
	public void testSendAudioBase64() {
		this.client.connect();
		byte[] pcm = new byte[] { 1, 2, 3, 4 };
		this.client.sendAudio(pcm);
		assertEquals(Base64.getEncoder().encodeToString(pcm), this.connector.socket.sent.get(0));
	}

	/** 未连接即发送抛异常。 */
	@Test
	public void testSendBeforeConnect() {
		assertThrows(AiException.class, () -> this.client.sendText("x"));
	}

	/** 收到转写事件 → onTranscript。 */
	@Test
	public void testOnTranscript() {
		this.client.connect();
		this.connector.listener.onText(this.connector.socket,
			"{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"你好\"}", true);
		assertEquals("你好", this.collector.transcript);
	}

	/** 收到音频 delta → onAudio（base64 解码）。 */
	@Test
	public void testOnAudio() {
		this.client.connect();
		byte[] pcm = new byte[] { 9, 8, 7 };
		String b64 = Base64.getEncoder().encodeToString(pcm);
		this.connector.listener.onText(this.connector.socket,
			"{\"type\":\"response.audio.delta\",\"delta\":\"" + b64 + "\"}", true);
		assertArrayEquals(pcm, this.collector.audio);
	}

	/** 收到错误事件 → onError。 */
	@Test
	public void testOnError() {
		this.client.connect();
		this.connector.listener.onText(this.connector.socket,
			"{\"type\":\"error\",\"error\":\"boom\"}", true);
		assertEquals("boom", this.collector.error);
	}

	/** 未识别事件 → onEvent 兜底。 */
	@Test
	public void testOnEvent() {
		this.client.connect();
		String raw = "{\"type\":\"session.created\",\"id\":\"sess-1\"}";
		this.connector.listener.onText(this.connector.socket, raw, true);
		assertEquals("session.created", this.collector.eventType);
		assertEquals(raw, this.collector.eventRaw);
	}

	/** close 关闭 WebSocket 并回调 onClose。 */
	@Test
	public void testClose() {
		this.client.connect();
		this.client.close();
		assertTrue(this.connector.socket.closed);
		assertFalse(this.client.isConnected());
		assertEquals(1, this.collector.closeCount);
	}

	/** WebSocket.onClose 回调断开连接。 */
	@Test
	public void testSocketOnClose() {
		this.client.connect();
		CompletionStage<?> cs = this.connector.listener.onClose(this.connector.socket, 1000, "done");
		assertFalse(this.client.isConnected());
		assertEquals(1, this.collector.closeCount);
		assertTrue(cs == null);
	}
}
