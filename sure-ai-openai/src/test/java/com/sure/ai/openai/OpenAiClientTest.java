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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * {@link OpenAiClient} 与 {@link OpenAiUtil} 集成测试：本地 HttpServer mock。
 *
 * <p>官方文档：https://platform.openai.com/docs/api-reference</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class OpenAiClientTest {

	private HttpServer server;
	private String baseUrl;
	private final java.util.concurrent.atomic.AtomicReference<String> lastBody =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastAuth =
		new java.util.concurrent.atomic.AtomicReference<>();
	private final java.util.concurrent.atomic.AtomicReference<String> lastOrg =
		new java.util.concurrent.atomic.AtomicReference<>();

	/** 启动本地服务并重置静态单例。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1";
		this.lastBody.set(null);
		this.lastAuth.set(null);
		this.lastOrg.set(null);
		resetUtil();
	}

	/** 停止服务并重置静态单例。 */
	@After
	public void tearDown() throws Exception {
		this.server.stop(0);
		resetUtil();
	}

	/** 反射清空 OpenAiUtil 静态单例，避免测试间串扰。 */
	private static void resetUtil() throws Exception {
		Field f = OpenAiUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		((SingletonHolder<?>) f.get(null)).reset();
	}

	/** 构造客户端（baseUrl 指向本地 mock）。 */
	private OpenAiClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("test-key").baseUrl(this.baseUrl).build();
		return new OpenAiClient(cfg);
	}

	/** 注册默认 JSON 处理器。 */
	private void handle(int status, String responseBody) {
		handle(exchange -> respond(exchange, status, responseBody));
	}

	/** 注册自定义处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			this.lastOrg.set(exchange.getRequestHeaders().getFirst("OpenAI-Organization"));
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			h.handle(exchange);
		});
	}

	/** 发送 JSON 响应。 */
	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 处理器函数式接口。 */
	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws IOException;
	}

	/** Bearer 头与 chat 请求体字段、响应解析。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"c1\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		OpenAiClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model(OpenAiModels.GPT_4O)
			.messages(ChatMessage.user("hi")).temperature(0.7).build());
		assertEquals("Bearer test-key", this.lastAuth.get());
		assertTrue(this.lastBody.get().contains("\"model\":\"gpt-4o\""));
		assertTrue(this.lastBody.get().contains("\"temperature\":0.7"));
		assertTrue(this.lastBody.get().contains("\"role\":\"user\""));
		assertEquals("c1", resp.id());
		assertEquals("hello", resp.firstText());
		assertEquals(8, resp.usage().totalTokens());
		client.close();
	}

	/** Organization 头。 */
	@Test
	public void testOrganizationHeader() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\"stop\"}]}");
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).organization("org-1").build();
		OpenAiClient client = new OpenAiClient(cfg);
		client.chat(ChatRequest.builder().model("gpt-4o").messages(ChatMessage.user("hi")).build());
		assertEquals("org-1", this.lastOrg.get());
		assertEquals("Bearer k", this.lastAuth.get());
		client.close();
	}

	/** SSE 流式聚合文本。 */
	@Test
	public void testChatStream() {
		String sse = "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"He\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":\"llo\"},\"index\":0}]}\n\n"
			+ "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
			+ "data: [DONE]\n\n";
		handle(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		OpenAiClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model("gpt-4o-mini").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("Hello", sb.toString());
		client.close();
	}

	/** embeddings 解析。 */
	@Test
	public void testEmbeddings() {
		handle(200, "{\"model\":\"text-embedding-3-small\",\"data\":[{\"embedding\":[0.1,0.2,0.3]}],"
			+ "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":0,\"total_tokens\":2}}");
		OpenAiClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest(OpenAiModels.TEXT_EMBEDDING_3_SMALL, List.of("hi")));
		assertTrue(this.lastBody.get().contains("\"model\":\"text-embedding-3-small\""));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 1e-6);
		client.close();
	}

	/** 图像生成（URL 格式）：请求体含 model/prompt，响应解析 firstUrl/revised_prompt/created。 */
	@Test
	public void testImageGeneration() {
		handle(200, "{\"created\":1700000000,\"data\":["
			+ "{\"url\":\"https://example.com/img.png\",\"revised_prompt\":\"a cat\"}]}");
		OpenAiClient client = newClient();
		ImageResponse resp = client.generate(ImageRequest.builder()
			.model(OpenAiModels.DALL_E_3).prompt("a cat").build());
		assertTrue(this.lastBody.get().contains("\"model\":\"dall-e-3\""));
		assertTrue(this.lastBody.get().contains("\"prompt\":\"a cat\""));
		assertEquals("https://example.com/img.png", resp.firstUrl());
		assertEquals("a cat", resp.data().get(0).revisedPrompt());
		assertEquals(1700000000L, resp.created());
		client.close();
	}

	/** 图像生成（b64_json 格式）：解析 b64 字段。 */
	@Test
	public void testImageB64Response() {
		handle(200, "{\"created\":1700000001,\"data\":["
			+ "{\"b64_json\":\"iVBORw0KGgoAAAANSUhEUg==\"}]}");
		OpenAiClient client = newClient();
		ImageResponse resp = client.generate(OpenAiModels.DALL_E_2, "a dog");
		assertTrue(this.lastBody.get().contains("\"model\":\"dall-e-2\""));
		assertTrue(this.lastBody.get().contains("\"prompt\":\"a dog\""));
		assertEquals("iVBORw0KGgoAAAANSUhEUg==", resp.firstB64());
		assertEquals(1700000001L, resp.created());
		client.close();
	}

	/** 视频生成（异步轮询）：提交 queued → 轮询 in_progress → completed。 */
	@Test
	public void testVideoGeneration() throws Exception {
		java.util.concurrent.atomic.AtomicInteger pollCount =
			new java.util.concurrent.atomic.AtomicInteger(0);
		handle(ex -> {
			String method = ex.getRequestMethod();
			String uri = ex.getRequestURI().toString();
			if ("POST".equals(method) && uri.endsWith("/videos")) {
				respond(ex, 200, "{\"id\":\"v-abc\",\"status\":\"queued\",\"created_at\":1700000000}");
			} else if ("GET".equals(method) && uri.contains("/videos/v-abc")) {
				int n = pollCount.incrementAndGet();
				if (n == 1) {
					respond(ex, 200, "{\"id\":\"v-abc\",\"status\":\"in_progress\",\"progress\":50}");
				} else {
					respond(ex, 200, "{\"id\":\"v-abc\",\"status\":\"completed\",\"created_at\":1700000000,"
						+ "\"data\":[{\"url\":\"https://cdn.example.com/video.mp4\"}]}");
				}
			} else {
				respond(ex, 404, "{\"error\":\"not found\"}");
			}
		});
		OpenAiClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.builder()
			.model(OpenAiModels.SORA_2).prompt("a cat playing piano").duration(5).build());
		assertEquals("https://cdn.example.com/video.mp4", resp.firstUrl());
		assertEquals(1700000000L, resp.created());
		assertEquals(2, pollCount.get());
		client.close();
	}

	/** 语音合成 TTS：POST JSON → 二进制音频响应。 */
	@Test
	public void testTts() {
		byte[] fakeAudio = new byte[]{0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x7f};
		handle(ex -> {
			ex.getResponseHeaders().set("Content-Type", "audio/mpeg");
			ex.sendResponseHeaders(200, fakeAudio.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(fakeAudio);
			}
		});
		OpenAiClient client = newClient();
		TtsResponse resp = client.synthesize(TtsRequest.builder()
			.model(OpenAiModels.TTS_1).input("hello").voice("alloy").responseFormat("mp3").build());
		assertTrue(this.lastBody.get().contains("\"model\":\"tts-1\""));
		assertTrue(this.lastBody.get().contains("\"input\":\"hello\""));
		assertTrue(this.lastBody.get().contains("\"voice\":\"alloy\""));
		assertEquals(fakeAudio.length, resp.audioLength());
		assertEquals("mp3", resp.format());
		client.close();
	}

	/** 语音识别 STT：multipart/form-data 上传 → JSON 文本响应。 */
	@Test
	public void testStt() {
		handle(200, "{\"text\":\"hello world\",\"language\":\"en\",\"duration\":2.5,"
			+ "\"segments\":[{\"id\":0,\"start\":0.0,\"end\":2.5,\"text\":\"hello world\"}],"
			+ "\"words\":[{\"word\":\"hello\",\"start\":0.0,\"end\":1.0},"
			+ "{\"word\":\"world\",\"start\":1.0,\"end\":2.5}]}");
		OpenAiClient client = newClient();
		byte[] audio = "fake-audio-data".getBytes(StandardCharsets.UTF_8);
		SttResponse resp = client.transcribe(SttRequest.builder()
			.model(OpenAiModels.WHISPER_1).audioData(audio).fileName("test.mp3")
			.contentType("audio/mpeg").language("en").build());
		// 验证请求为 multipart
		assertTrue(this.lastBody.get().contains("multipart/form-data")
			|| this.lastBody.get().contains("------sureai"));
		assertEquals("hello world", resp.text());
		assertEquals("en", resp.language());
		assertEquals(Double.valueOf(2.5), resp.duration());
		assertEquals(1, resp.segments().size());
		assertEquals(2, resp.words().size());
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		OpenAiClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("gpt-4o").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 400 映射为 AiApiException。 */
	@Test
	public void testBadRequest400() {
		handle(400, "{\"error\":\"bad\"}");
		OpenAiClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chat(
			ChatRequest.builder().model("gpt-4o").messages(ChatMessage.user("hi")).build()));
		assertEquals(400, e.getHttpStatus());
		client.close();
	}

	/** baseUrl 为空时使用默认地址（仅构造，不发起网络请求）。 */
	@Test
	public void testDefaultBaseUrlWhenNull() {
		OpenAiClient client = new OpenAiClient(AiConfig.of("k"));
		assertEquals("openai", client.name());
		client.close();
	}

	/** Util 显式 init 后便捷 chat/embed。 */
	@Test
	public void testUtilConvenience() {
		handle(200, "{\"id\":\"u\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		OpenAiUtil.init(AiConfig.builder().apiKey("util-key").baseUrl(this.baseUrl).build());
		assertEquals("yo", OpenAiUtil.chat("gpt-4o", "hi").firstText());
		assertEquals("Bearer util-key", this.lastAuth.get());
	}

	/** Util 便捷 TTS：二进制音频响应。 */
	@Test
	public void testUtilTts() {
		byte[] audio = new byte[]{1, 2, 3, 4};
		handle(ex -> {
			ex.getResponseHeaders().set("Content-Type", "audio/mpeg");
			ex.sendResponseHeaders(200, audio.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(audio);
			}
		});
		OpenAiUtil.init(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
		assertEquals(4, OpenAiUtil.tts("tts-1", "hi", "alloy").audioLength());
	}

	/** Util 便捷 STT：multipart 上传 → JSON 文本响应。 */
	@Test
	public void testUtilStt() {
		handle(200, "{\"text\":\"transcribed\"}");
		OpenAiUtil.init(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
		assertEquals("transcribed", OpenAiUtil.stt("whisper-1", new byte[]{1, 2}).text());
	}

	/** Util 便捷 video：异步轮询 queued → completed。 */
	@Test
	public void testUtilVideo() {
		java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger(0);
		handle(ex -> {
			String method = ex.getRequestMethod();
			if ("POST".equals(method)) {
				respond(ex, 200, "{\"id\":\"v1\",\"status\":\"queued\"}");
			} else {
				int n = polls.incrementAndGet();
				if (n == 1) {
					respond(ex, 200, "{\"id\":\"v1\",\"status\":\"in_progress\"}");
				} else {
					respond(ex, 200, "{\"id\":\"v1\",\"status\":\"completed\",\"data\":[{\"url\":\"http://x/v.mp4\"}]}");
				}
			}
		});
		OpenAiUtil.init(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
		assertEquals("http://x/v.mp4", OpenAiUtil.video("sora-2", "cat").firstUrl());
	}

	/** Util 未 init 且未设置环境变量时抛 AiException。 */
	@Test
	public void testUtilMissingEnvKey() {
		assertThrows(AiException.class, OpenAiUtil::client);
	}

	/** buildConfig：带 baseUrl 与不带 baseUrl 两种路径。 */
	@Test
	public void testBuildConfig() {
		AiConfig withUrl = OpenAiUtil.buildConfig("k", "http://mock");
		assertEquals("http://mock", withUrl.baseUrl());
		AiConfig noUrl = OpenAiUtil.buildConfig("k", " ");
		assertEquals(null, noUrl.baseUrl());
		assertThrows(AiException.class, () -> OpenAiUtil.buildConfigFromEnv());
	}

	/** Models 私有构造器不可实例化。 */
	@Test
	public void testModelsConstructor() throws Exception {
		java.lang.reflect.Constructor<OpenAiModels> c = OpenAiModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		// 反射调用私有构造器，AssertionError 被包装为 InvocationTargetException
		java.lang.reflect.InvocationTargetException ex = assertThrows(
			java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
		assertEquals("gpt-4o", OpenAiModels.GPT_4O);
	}
}
