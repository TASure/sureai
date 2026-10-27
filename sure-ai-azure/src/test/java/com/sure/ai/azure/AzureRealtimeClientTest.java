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

package com.sure.ai.azure;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.Before;
import org.junit.Test;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.realtime.RealtimeConnector;
import com.sure.ai.client.realtime.RealtimeEventListener;

/**
 * {@link AzureRealtimeClient} 测试：FakeConnector + FakeWebSocket，零真实网络。
 *
 * <p>官方文档：https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/realtime-audio-websockets</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class AzureRealtimeClientTest {

	/** 内存事件收集器。 */
	private static final class Collector implements RealtimeEventListener {

		String transcript;
		byte[] audio;
		String error;
		String eventType;
		int speechStart;
		int speechStop;
		int interrupted;

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
	private AzureRealtimeClient client;

	/** 初始化。 */
	@Before
	public void setUp() {
		this.collector = new Collector();
		this.connector = new FakeConnector();
		AiConfig cfg = AiConfig.builder().apiKey("azure-key")
			.baseUrl("https://my-resource.openai.azure.com").build();
		this.client = new AzureRealtimeClient(cfg, "gpt-realtime", this.connector, this.collector);
	}

	/** buildUri：https→wss，/openai/v1/realtime?model= 部署名。 */
	@Test
	public void testBuildUri() {
		this.client.connect();
		String uri = this.connector.uri.toString();
		assertTrue(uri, uri.startsWith("wss://my-resource.openai.azure.com"));
		assertTrue(uri, uri.contains("/openai/v1/realtime?model=gpt-realtime"));
		assertEquals("azure-realtime", this.client.name());
		assertEquals("gpt-realtime", this.client.deployment());
	}

	/** sendText：先发 conversation.item.create 再发 response.create。 */
	@Test
	public void testSendText() {
		this.client.connect();
		this.client.sendText("你好");
		assertEquals(2, this.connector.socket.sent.size());
		assertTrue(this.connector.socket.sent.get(0).contains("conversation.item.create"));
		assertTrue(this.connector.socket.sent.get(0).contains("你好"));
		assertTrue(this.connector.socket.sent.get(1).contains("response.create"));
	}

	/** sendAudio：封装为 input_audio_buffer.append（base64）。 */
	@Test
	public void testSendAudio() {
		this.client.connect();
		this.client.sendAudio(new byte[] { 1, 2, 3 });
		String sent = this.connector.socket.sent.get(0);
		assertTrue(sent, sent.contains("input_audio_buffer.append"));
		assertTrue(sent, sent.contains(Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 })));
	}

	/** response.output_audio.delta → onAudio。 */
	@Test
	public void testAudioDelta() {
		this.client.connect();
		byte[] pcm = new byte[] { 5, 6, 7 };
		String b64 = Base64.getEncoder().encodeToString(pcm);
		deliver("{\"type\":\"response.output_audio.delta\",\"delta\":\"" + b64 + "\"}");
		assertArrayEquals(pcm, this.collector.audio);
	}

	/** response.audio_transcript.delta → onTranscript。 */
	@Test
	public void testTranscriptDelta() {
		this.client.connect();
		deliver("{\"type\":\"response.audio_transcript.delta\",\"delta\":\"你好\"}");
		assertEquals("你好", this.collector.transcript);
	}

	/** input_audio_transcription.completed → onTranscript。 */
	@Test
	public void testTranscriptCompleted() {
		this.client.connect();
		deliver("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"abc\"}");
		assertEquals("abc", this.collector.transcript);
	}

	/** error → onError。 */
	@Test
	public void testError() {
		this.client.connect();
		deliver("{\"type\":\"error\",\"error\":{\"code\":\"x\",\"message\":\"boom\"}}");
		assertEquals("boom", this.collector.error);
	}

	/** 未知事件 → onEvent 携带类型。 */
	@Test
	public void testUnknownEvent() {
		this.client.connect();
		deliver("{\"type\":\"session.updated\",\"foo\":1}");
		assertEquals("session.updated", this.collector.eventType);
	}

	/** VAD speech_started → onSpeechStart（与 OpenAI 协议对齐）。 */
	@Test
	public void testVadSpeechStart() {
		this.client.connect();
		deliver("{\"type\":\"input_audio_buffer.speech_started\"}");
		assertEquals(1, this.collector.speechStart);
	}

	/** VAD speech_stopped → onSpeechStop。 */
	@Test
	public void testVadSpeechStop() {
		this.client.connect();
		deliver("{\"type\":\"input_audio_buffer.speech_stopped\"}");
		assertEquals(1, this.collector.speechStop);
	}

	/** conversation.interrupted → onInterrupted。 */
	@Test
	public void testInterrupted() {
		this.client.connect();
		deliver("{\"type\":\"conversation.interrupted\"}");
		assertEquals(1, this.collector.interrupted);
	}

	/** response.output_audio.interrupted → onInterrupted。 */
	@Test
	public void testOutputAudioInterrupted() {
		this.client.connect();
		deliver("{\"type\":\"response.output_audio.interrupted\"}");
		assertEquals(1, this.collector.interrupted);
	}

	/** error 为字符串 → onError 携带字符串。 */
	@Test
	public void testErrorString() {
		this.client.connect();
		deliver("{\"type\":\"error\",\"error\":\"plain-text-err\"}");
		assertEquals("plain-text-err", this.collector.error);
	}

	/** error 缺失 → onError 携带原始消息。 */
	@Test
	public void testErrorAbsent() {
		this.client.connect();
		deliver("{\"type\":\"error\",\"weird\":1}");
		assertNotNull(this.collector.error);
	}

	/** buildUri：baseUrl 为空 → wss://localhost。 */
	@Test
	public void testBuildUriNoBase() {
		AiConfig cfg = AiConfig.builder().apiKey("k").build();
		AzureRealtimeClient c = new AzureRealtimeClient(cfg, "gpt-realtime", this.connector, this.collector);
		c.connect();
		assertTrue(this.connector.uri.toString().startsWith("wss://localhost"));
	}

	/** buildUri：baseUrl 以斜杠结尾 → 去除尾部斜杠。 */
	@Test
	public void testBuildUriTrailingSlash() {
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl("https://x.openai.azure.com/").build();
		AzureRealtimeClient c = new AzureRealtimeClient(cfg, "gpt-realtime", this.connector, this.collector);
		c.connect();
		String uri = this.connector.uri.toString();
		assertTrue(uri, uri.startsWith("wss://x.openai.azure.com/openai"));
	}

	/** 投递一帧下行消息。 */
	private void deliver(String json) {
		this.connector.listener.onText(this.connector.socket, json, true);
	}
}
