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
package com.sure.ai.openai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.Before;
import org.junit.Test;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.realtime.RealtimeConnector;
import com.sure.ai.client.realtime.RealtimeEventListener;

/**
 * {@link OpenAiRealtimeClient} 测试：FakeConnector + FakeWebSocket，零真实网络。
 *
 * @author sureai
 * @since 0.2.0
 */
public class OpenAiRealtimeClientTest {

	/** 内存事件收集器。 */
	private static final class Collector implements RealtimeEventListener {

		String transcript;
		byte[] audio;
		String error;
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
		public void onError(String err) {
			this.error = err;
		}

		@Override
		public void onClose() {
		}

		@Override
		public void onEvent(String type, String rawJson) {
			this.eventType = type;
			this.eventRaw = rawJson;
		}
	}

	/** Fake WebSocket：记录发送文本。 */
	static final class FakeWebSocket implements WebSocket {

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

	/** Fake 连接器。 */
	static final class FakeConnector implements RealtimeConnector {

		final FakeWebSocket socket = new FakeWebSocket();
		WebSocket.Listener listener;
		URI uri;

		@Override
		public WebSocket connect(URI u, WebSocket.Listener l) {
			this.uri = u;
			this.listener = l;
			l.onOpen(this.socket);
			return this.socket;
		}
	}

	private Collector collector;
	private FakeConnector connector;
	private OpenAiRealtimeClient client;

	/** 初始化。 */
	@Before
	public void setUp() {
		this.collector = new Collector();
		this.connector = new FakeConnector();
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl("https://api.openai.com/v1").build();
		this.client = new OpenAiRealtimeClient(cfg, "gpt-4o-realtime", this.connector,
			this.collector);
	}

	/** buildUri 含 model 且走 wss。 */
	@Test
	public void testBuildUri() {
		this.client.connect();
		String uri = this.connector.uri.toString();
		assertTrue(uri, uri.startsWith("wss://api.openai.com/v1/realtime?model="));
		assertTrue(uri, uri.contains("gpt-4o-realtime"));
	}

	/** 默认 baseUrl 为空时使用官方地址。 */
	@Test
	public void testBuildUriDefaultBase() {
		AiConfig cfg = AiConfig.of("k");
		OpenAiRealtimeClient c = new OpenAiRealtimeClient(cfg, "m", this.connector, this.collector);
		c.connect();
		assertEquals("wss://api.openai.com/v1/realtime?model=m", this.connector.uri.toString());
	}

	/** 收到音频 delta → onAudio（base64 解码）。 */
	@Test
	public void testHandleMessageAudio() {
		this.client.connect();
		byte[] pcm = new byte[] { 1, 2, 3, 4 };
		String b64 = Base64.getEncoder().encodeToString(pcm);
		deliver("{\"type\":\"response.output_audio.delta\",\"delta\":\"" + b64 + "\"}");
		assertArrayEquals(pcm, this.collector.audio);
	}

	/** 收到转写 delta → onTranscript。 */
	@Test
	public void testHandleMessageTranscriptDelta() {
		this.client.connect();
		deliver("{\"type\":\"response.audio_transcript.delta\",\"delta\":\"你好\"}");
		assertEquals("你好", this.collector.transcript);
	}

	/** 收到转写完成事件 → onTranscript。 */
	@Test
	public void testHandleMessageTranscriptCompleted() {
		this.client.connect();
		deliver("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"世界\"}");
		assertEquals("世界", this.collector.transcript);
	}

	/** 收到错误事件 → onError（嵌套 error.message）。 */
	@Test
	public void testHandleMessageError() {
		this.client.connect();
		deliver("{\"type\":\"error\",\"error\":{\"message\":\"boom\"}}");
		assertEquals("boom", this.collector.error);
	}

	/** 未识别事件 → onEvent 兜底。 */
	@Test
	public void testOnEvent() {
		this.client.connect();
		String raw = "{\"type\":\"session.created\",\"id\":\"s1\"}";
		deliver(raw);
		assertEquals("session.created", this.collector.eventType);
		assertEquals(raw, this.collector.eventRaw);
	}

	/** sendText 发送两条事件。 */
	@Test
	public void testSendText() {
		this.client.connect();
		this.client.sendText("hi");
		assertEquals(2, this.connector.socket.sent.size());
		assertTrue(this.connector.socket.sent.get(0),
			this.connector.socket.sent.get(0).contains("conversation.item.create"));
		assertTrue(this.connector.socket.sent.get(0),
			this.connector.socket.sent.get(0).contains("hi"));
		assertEquals("{\"type\":\"response.create\"}", this.connector.socket.sent.get(1));
	}

	/** sendAudio 封装为 input_audio_buffer.append。 */
	@Test
	public void testSendAudio() {
		this.client.connect();
		byte[] pcm = new byte[] { 9, 8, 7 };
		this.client.sendAudio(pcm);
		String sent = this.connector.socket.sent.get(0);
		assertTrue(sent, sent.contains("input_audio_buffer.append"));
		assertTrue(sent, sent.contains(Base64.getEncoder().encodeToString(pcm)));
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("openai-realtime", this.client.name());
	}

	/** 向 InternalListener 投递文本帧。 */
	private void deliver(String json) {
		CompletionStage<?> cs = this.connector.listener.onText(this.connector.socket, json, true);
		assertTrue(cs == null);
	}
}
