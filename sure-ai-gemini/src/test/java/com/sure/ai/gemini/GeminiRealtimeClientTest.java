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
package com.sure.ai.gemini;

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
 * {@link GeminiRealtimeClient} 测试：FakeConnector + FakeWebSocket，零真实网络。
 *
 * @author sureai
 * @since 0.2.0
 */
public class GeminiRealtimeClientTest {

	/** 内存事件收集器。 */
	private static final class Collector implements RealtimeEventListener {

		String transcript;
		byte[] audio;
		String error;
		String eventType;

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
		}
	}

	/** Fake WebSocket。 */
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
	private GeminiRealtimeClient client;

	/** 初始化。 */
	@Before
	public void setUp() {
		this.collector = new Collector();
		this.connector = new FakeConnector();
		AiConfig cfg = AiConfig.of("gemini-key");
		this.client = new GeminiRealtimeClient(cfg, "gemini-2.0-flash-exp", this.connector,
			this.collector);
	}

	/** buildUri 含 BidiGenerateContent 路径与 ?key=。 */
	@Test
	public void testBuildUri() {
		this.client.connect();
		String uri = this.connector.uri.toString();
		assertTrue(uri, uri.contains("BidiGenerateContent?key=gemini-key"));
		assertTrue(uri, uri.startsWith("wss://"));
	}

	/** connect 后自动发送 setup（含 models/ 前缀模型）。 */
	@Test
	public void testConnectSendsSetup() {
		this.client.connect();
		assertEquals(1, this.connector.socket.sent.size());
		String setup = this.connector.socket.sent.get(0);
		assertTrue(setup, setup.contains("\"setup\""));
		assertTrue(setup, setup.contains("models/gemini-2.0-flash-exp"));
	}

	/** setup 只发一次。 */
	@Test
	public void testSetupSentOnce() {
		this.client.connect();
		this.client.connect();
		assertEquals(1, this.connector.socket.sent.size());
	}

	/** serverContent 音频分片 → onAudio。 */
	@Test
	public void testServerContentAudio() {
		this.client.connect();
		byte[] pcm = new byte[] { 5, 6, 7 };
		String b64 = Base64.getEncoder().encodeToString(pcm);
		String json = "{\"serverContent\":{\"modelTurn\":{\"parts\":["
			+ "{\"inlineData\":{\"mimeType\":\"audio/pcm\",\"data\":\"" + b64 + "\"}}]}}}";
		deliver(json);
		assertArrayEquals(pcm, this.collector.audio);
	}

	/** serverContent 文本分片 → onTranscript。 */
	@Test
	public void testServerContentText() {
		this.client.connect();
		String json = "{\"serverContent\":{\"modelTurn\":{\"parts\":["
			+ "{\"text\":\"你好\"}]}}}";
		deliver(json);
		assertEquals("你好", this.collector.transcript);
	}

	/** error → onError。 */
	@Test
	public void testError() {
		this.client.connect();
		deliver("{\"error\":{\"code\":500,\"message\":\"boom\"}}");
		assertEquals("boom", this.collector.error);
	}

	/** toolCallCancellation → onError。 */
	@Test
	public void testToolCallCancellation() {
		this.client.connect();
		deliver("{\"toolCallCancellation\":{\"id\":\"1\"}}");
		assertEquals("toolCallCancellation", this.collector.error);
	}

	/** setupComplete 等未知事件 → onEvent。 */
	@Test
	public void testOnEvent() {
		this.client.connect();
		deliver("{\"setupComplete\":{}}");
		assertEquals("setupComplete", this.collector.eventType);
	}

	/** sendText 封装为 realtimeInput.text。 */
	@Test
	public void testSendText() {
		this.client.connect();
		this.client.sendText("hi");
		// sent[0]=setup, sent[1]=realtimeInput
		String sent = this.connector.socket.sent.get(1);
		assertTrue(sent, sent.contains("\"realtimeInput\""));
		assertTrue(sent, sent.contains("\"text\":\"hi\""));
	}

	/** sendAudio 封装为 realtimeInput.chunks[].inlineData。 */
	@Test
	public void testSendAudio() {
		this.client.connect();
		byte[] pcm = new byte[] { 1, 2 };
		this.client.sendAudio(pcm);
		String sent = this.connector.socket.sent.get(1);
		assertTrue(sent, sent.contains("\"chunks\""));
		assertTrue(sent, sent.contains("\"inlineData\""));
		assertTrue(sent, sent.contains(Base64.getEncoder().encodeToString(pcm)));
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("gemini-realtime", this.client.name());
	}

	/** 投递文本帧。 */
	private void deliver(String json) {
		CompletionStage<?> cs = this.connector.listener.onText(this.connector.socket, json, true);
		assertTrue(cs == null);
	}
}
