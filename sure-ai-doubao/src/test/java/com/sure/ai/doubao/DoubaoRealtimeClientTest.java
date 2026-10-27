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

package com.sure.ai.doubao;

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
 * {@link DoubaoRealtimeClient} 测试：FakeConnector + FakeWebSocket，零真实网络。
 *
 * @author sureai
 * @since 0.2.0
 */
public class DoubaoRealtimeClientTest {

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
	private DoubaoRealtimeClient client;

	/** 初始化。 */
	@Before
	public void setUp() {
		this.collector = new Collector();
		this.connector = new FakeConnector();
		AiConfig cfg = AiConfig.of("doubao-key");
		this.client = new DoubaoRealtimeClient(cfg, "ep-realtime", this.connector, this.collector);
	}

	/** buildUri。 */
	@Test
	public void testBuildUri() {
		this.client.connect();
		assertEquals("wss://openspeech.bytedance.com/api/v3/auc/realtime",
			this.connector.uri.toString());
	}

	/** audio 事件 → onAudio。 */
	@Test
	public void testHandleMessageAudio() {
		this.client.connect();
		byte[] pcm = new byte[] { 4, 5 };
		String b64 = Base64.getEncoder().encodeToString(pcm);
		deliver("{\"type\":\"audio\",\"data\":\"" + b64 + "\"}");
		assertArrayEquals(pcm, this.collector.audio);
	}

	/** transcript 事件 → onTranscript。 */
	@Test
	public void testTranscript() {
		this.client.connect();
		deliver("{\"type\":\"transcript\",\"text\":\"你好\"}");
		assertEquals("你好", this.collector.transcript);
	}

	/** error → onError。 */
	@Test
	public void testError() {
		this.client.connect();
		deliver("{\"type\":\"error\",\"message\":\"boom\"}");
		assertEquals("boom", this.collector.error);
	}

	/** 未知事件 → onEvent。 */
	@Test
	public void testOnEvent() {
		this.client.connect();
		deliver("{\"type\":\"connected\"}");
		assertEquals("connected", this.collector.eventType);
	}

	/** sendAudio。 */
	@Test
	public void testSendAudio() {
		this.client.connect();
		byte[] pcm = new byte[] { 1, 2 };
		this.client.sendAudio(pcm);
		String sent = this.connector.socket.sent.get(0);
		assertTrue(sent, sent.contains("\"type\":\"audio\""));
		assertTrue(sent, sent.contains(Base64.getEncoder().encodeToString(pcm)));
	}

	/** name() / model()。 */
	@Test
	public void testNameAndModel() {
		assertEquals("doubao-realtime", this.client.name());
		assertEquals("ep-realtime", this.client.model());
	}

	/** 投递文本帧。 */
	private void deliver(String json) {
		CompletionStage<?> cs = this.connector.listener.onText(this.connector.socket, json, true);
		assertTrue(cs == null);
	}
}
