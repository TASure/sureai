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

package com.sure.ai.qwen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.TtsRequest;

/**
 * {@link QwenClient} 协议改写边界分支测试：thinkingConfig 多形态、reasoningEffort 映射、
 * TTS/STT 默认值、原生端点 extraHeader/错误/中断路径。
 *
 * <p>零真实网络：本地 HttpServer mock。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public class QwenClientExtraTest {

	private static final String TTS_PATH = "/api/v1/services/audio/tts/SpeechSynthesizer";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastPath = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastCustomHeader = new AtomicReference<>();
	private String ttsResponse = "{\"output\":{\"audio\":{\"url\":\"https://example.com/a.mp3\"}}}";
	private String sttResponse = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"识别\"}}]}";
	private int ttsStatus = 200;

	/** 启动 mock。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/compatible-mode/v1";
		this.server.createContext("/", this::handle);
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 路由：chat/completions 与原生 TTS 端点。 */
	private void handle(HttpExchange ex) throws IOException {
		this.lastPath.set(ex.getRequestURI().getPath());
		this.lastCustomHeader.set(ex.getRequestHeaders().getFirst("X-Custom"));
		byte[] in = ex.getRequestBody().readAllBytes();
		this.lastBody.set(new String(in, StandardCharsets.UTF_8));
		String body;
		int status = 200;
		if (this.lastPath.get().endsWith(TTS_PATH)) {
			body = this.ttsResponse;
			status = this.ttsStatus;
		}
		else {
			body = this.sttResponse;
		}
		byte[] out = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	/** 构造客户端。 */
	private QwenClient newClient() {
		return new QwenClient(AiConfig.builder().apiKey("sk").baseUrl(this.baseUrl).build());
	}

	/** thinkingConfig 为字符串 "true" → enable_thinking=true。 */
	@Test
	public void testThinkingConfigStringTrue() {
		QwenClient client = newClient();
		client.chat(ChatRequest.builder().model("qwen-plus").messages(ChatMessage.user("hi"))
			.thinkingConfig("true").build());
		assertTrue(this.lastBody.get().contains("\"enable_thinking\":true"));
		client.close();
	}

	/** thinkingConfig 为 Map（含 thinking_budget）→ enable_thinking + thinking_budget。 */
	@Test
	public void testThinkingConfigMapBudget() {
		QwenClient client = newClient();
		client.chat(ChatRequest.builder().model("qwen-plus").messages(ChatMessage.user("hi"))
			.thinkingConfig(Map.of("thinking_budget", 2048)).build());
		assertTrue(this.lastBody.get().contains("\"enable_thinking\":true"));
		assertTrue(this.lastBody.get().contains("\"thinking_budget\":2048"));
		client.close();
	}

	/** thinkingConfig 为 JsonObject（含 thinking_budget）→ JsonObject 分支。 */
	@Test
	public void testThinkingConfigJsonObjectBudget() {
		JsonObject cfg = Json.object();
		cfg.put("thinking_budget", 1024);
		QwenClient client = newClient();
		client.chat(ChatRequest.builder().model("qwen-plus").messages(ChatMessage.user("hi"))
			.thinkingConfig(cfg).build());
		assertTrue(this.lastBody.get().contains("\"thinking_budget\":1024"));
		client.close();
	}

	/** reasoningEffort 映射：minimal→512、low→1024、medium→4096。 */
	@Test
	public void testReasoningEffortBudgets() {
		QwenClient client = newClient();
		client.chat(ChatRequest.builder().model("qwen-plus").messages(ChatMessage.user("hi"))
			.reasoningEffort("minimal").build());
		assertTrue(this.lastBody.get(), this.lastBody.get().contains("\"thinking_budget\":512"));
		client.close();

		client = newClient();
		client.chat(ChatRequest.builder().model("qwen-plus").messages(ChatMessage.user("hi"))
			.reasoningEffort("low").build());
		assertTrue(this.lastBody.get(), this.lastBody.get().contains("\"thinking_budget\":1024"));
		client.close();

		client = newClient();
		client.chat(ChatRequest.builder().model("qwen-plus").messages(ChatMessage.user("hi"))
			.reasoningEffort("medium").build());
		assertTrue(this.lastBody.get(), this.lastBody.get().contains("\"thinking_budget\":4096"));
		client.close();
	}

	/** effortToBudget(null) 防御分支返回 null。 */
	@Test
	public void testEffortToBudgetNull() throws Exception {
		Method m = QwenClient.class.getDeclaredMethod("effortToBudget", String.class);
		m.setAccessible(true);
		assertNull(m.invoke(null, new Object[] { null }));
	}

	/** TTS 未指定 sampleRate/volume/speed 时落入缺省值。 */
	@Test
	public void testTtsDefaultOptions() {
		QwenClient client = newClient();
		client.synthesize(TtsRequest.builder().model("cosyvoice").input("你好").voice("v").build());
		assertTrue(this.lastBody.get().contains("\"sample_rate\":22050"));
		assertTrue(this.lastBody.get().contains("\"volume\":50.0"));
		assertTrue(this.lastBody.get().contains("\"rate\":1.0"));
		client.close();
	}

	/** STT 未指定 contentType 时默认 audio/wav。 */
	@Test
	public void testSttDefaultContentType() {
		QwenClient client = newClient();
		client.transcribe(SttRequest.builder().model("asr").audioData(new byte[] { 1, 2 }).build());
		assertTrue(this.lastBody.get().contains("data:audio/wav;base64,"));
		client.close();
	}

	/** 原生 TTS 端点携带 extraHeaders。 */
	@Test
	public void testTtsExtraHeader() {
		QwenClient client = new QwenClient(AiConfig.builder().apiKey("sk").baseUrl(this.baseUrl)
			.extraHeader("X-Custom", "custom-value").build());
		client.synthesize(TtsRequest.builder().model("cosyvoice").input("x").voice("v").build());
		assertEquals("custom-value", this.lastCustomHeader.get());
		client.close();
	}

	/** 原生 TTS 端点 5xx → mapError 映射为 AiApiException。 */
	@Test
	public void testTtsNon2xx() {
		this.ttsStatus = 500;
		this.ttsResponse = "{\"code\":500,\"message\":\"boom\"}";
		QwenClient client = newClient();
		assertThrows(AiApiException.class, () -> client.synthesize(
			TtsRequest.builder().model("cosyvoice").input("x").voice("v").build()));
		client.close();
	}

	/** 原生 TTS 端点 IO 失败 → AiTimeoutException。 */
	@Test
	public void testTtsIoFailure() {
		QwenClient dead = new QwenClient(
			AiConfig.builder().apiKey("sk").baseUrl("http://127.0.0.1:9").build());
		assertThrows(AiTimeoutException.class, () -> dead.synthesize(
			TtsRequest.builder().model("cosyvoice").input("x").voice("v").build()));
		dead.close();
	}

	/** 原生 TTS 端点被中断 → AiException(qwen tts request interrupted)。 */
	@Test
	public void testTtsInterrupted() throws Exception {
		this.server.removeContext("/");
		this.server.createContext("/", ex -> {
			try {
				Thread.sleep(2000L);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			byte[] out = this.ttsResponse.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, out.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(out);
			}
		});
		QwenClient client = newClient();
		Thread caller = Thread.currentThread();
		Thread watcher = new Thread(() -> {
			try {
				Thread.sleep(200L);
				caller.interrupt();
			}
			catch (InterruptedException ignored) {
			}
		});
		watcher.start();
		AiException e = assertThrows(AiException.class, () -> client.synthesize(
			TtsRequest.builder().model("cosyvoice").input("x").voice("v").build()));
		assertTrue(e.getMessage(), e.getMessage().contains("qwen tts request interrupted"));
		watcher.join();
		Thread.interrupted();
		client.close();
	}
}
