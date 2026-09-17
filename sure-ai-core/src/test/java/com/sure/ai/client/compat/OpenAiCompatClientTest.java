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

package com.sure.ai.client.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.CacheControl;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;
import com.sure.ai.model.GroundingSource;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.Model;
import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link OpenAiCompatClient} 集成测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class OpenAiCompatClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastOrg = new AtomicReference<>();
	private final AtomicInteger requestCount = new AtomicInteger();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1";
		this.lastBody.set(null);
		this.lastAuth.set(null);
		this.lastOrg.set(null);
		this.requestCount.set(0);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private OpenAiCompatClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("test-key").baseUrl(this.baseUrl).build();
		return new OpenAiCompatClient(cfg);
	}

	/** 注册默认处理器。 */
	private void handle(int status, String responseBody) {
		handle(exchange -> respond(exchange, status, responseBody));
	}

	/** 注册自定义处理器。 */
	private void handle(Handler h) {
		this.server.createContext("/", exchange -> {
			this.requestCount.incrementAndGet();
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

	/** 正常 chat：校验请求头与请求体字段。 */
	@Test
	public void testChat() {
		handle(200, "{\"id\":\"c1\",\"model\":\"gpt\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
			+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}");
		OpenAiCompatClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user("hi")).temperature(0.7).build());
		assertEquals("Bearer test-key", this.lastAuth.get());
		assertTrue(this.lastBody.get().contains("\"model\":\"gpt\""));
		assertTrue(this.lastBody.get().contains("\"temperature\":0.7"));
		assertTrue(this.lastBody.get().contains("\"role\":\"user\""));
		assertEquals("c1", resp.id());
		assertEquals("hello", resp.firstText());
		assertEquals(8, resp.usage().totalTokens());
		assertEquals("stop", resp.choices().get(0).finishReason());
		client.close();
	}

	/** 流式 chat：聚合 deltaText。 */
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
		OpenAiCompatClient client = newClient();
		StringBuilder sb = new StringBuilder();
		String[] finish = new String[1];
		client.chatStream(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
				if (chunk.finishReason() != null) {
					finish[0] = chunk.finishReason();
				}
			});
		assertEquals("Hello", sb.toString());
		assertEquals("stop", finish[0]);
		client.close();
	}

	/** embeddings。 */
	@Test
	public void testEmbeddings() {
		handle(200, "{\"model\":\"emb\",\"data\":[{\"embedding\":[0.1,0.2,0.3]}],"
			+ "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":0,\"total_tokens\":2}}");
		OpenAiCompatClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest("emb", List.of("hi")));
		assertTrue(this.lastBody.get().contains("\"model\":\"emb\""));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 1e-6);
		client.close();
	}

	/** 429 重试后成功。 */
	@Test
	public void testRetry429() {
		handle(ex -> {
			int count = this.requestCount.get();
			if (count == 1) {
				ex.getResponseHeaders().set("Retry-After", "1");
				respond(ex, 429, "{\"error\":\"busy\"}");
			} else {
				respond(ex, 200, "{\"id\":\"c2\",\"choices\":[{\"index\":0,"
					+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
			}
		});
		OpenAiCompatClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build());
		assertEquals("ok", resp.firstText());
		assertEquals(2, this.requestCount.get());
		client.close();
	}

	/** 401 映射为 AiAuthException。 */
	@Test
	public void testAuth401() {
		handle(401, "{\"error\":\"unauthorized\"}");
		OpenAiCompatClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 400 映射为 AiApiException。 */
	@Test
	public void testBadRequest400() {
		handle(400, "{\"error\":\"bad\"}");
		OpenAiCompatClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chat(
			ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build()));
		assertEquals(400, e.getHttpStatus());
		assertTrue(e.getRawBody().contains("bad"));
		client.close();
	}

	/** function calling：请求带 tools，响应带 tool_calls。 */
	@Test
	public void testFunctionCalling() {
		handle(200, "{\"id\":\"c3\",\"model\":\"gpt\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":null,"
			+ "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
			+ "\"function\":{\"name\":\"getWeather\",\"arguments\":\"{\\\"city\\\":\\\"X\\\"}\"}}]},"
			+ "\"finish_reason\":\"tool_calls\"}]}");
		OpenAiCompatClient client = newClient();
		ToolFunction fn = ToolFunction.of("getWeather", "天气", "{\"type\":\"object\"}");
		ChatResponse resp = client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user("北京天气"))
			.tools(List.of(ToolSpec.of(fn))).build());
		assertTrue(this.lastBody.get().contains("\"tools\""));
		assertTrue(this.lastBody.get().contains("getWeather"));
		List<ToolCall> calls = resp.choices().get(0).message().toolCalls();
		assertNotNull(calls);
		assertEquals("call_1", calls.get(0).id());
		assertEquals("getWeather", calls.get(0).name());
		assertTrue(calls.get(0).argumentsJson().contains("city"));
		client.close();
	}

	/** 多模态：user 消息带 ImagePart。 */
	@Test
	public void testMultimodal() {
		handle(200, "{\"id\":\"c4\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"see\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		List<MessagePart> parts = List.of(TextPart.of("描述"), ImagePart.ofUrl("http://img/x.png"));
		client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user(parts)).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"type\":\"image_url\""));
		assertTrue(body.contains("http://img/x.png"));
		assertTrue(body.contains("\"type\":\"text\""));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("openai-compat", newClient().name());
	}

	/** organization 头。 */
	@Test
	public void testOrganizationHeader() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\"stop\"}]}");
		AiConfig cfg = AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).organization("org-1").build();
		OpenAiCompatClient client = new OpenAiCompatClient(cfg);
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build());
		assertEquals("org-1", this.lastOrg.get());
		client.close();
	}

	/** 便捷 chat(model, prompt)。 */
	@Test
	public void testConvenienceChat() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"yo\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		assertEquals("yo", client.chat("gpt", "hi").firstText());
		client.close();
	}

	/** 便捷 embed(model, text)。 */
	@Test
	public void testConvenienceEmbed() {
		handle(200, "{\"model\":\"e\",\"data\":[{\"embedding\":[1.0]}],"
			+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"total_tokens\":1}}");
		OpenAiCompatClient client = newClient();
		assertEquals(1, client.embed("e", "hi").embeddings().size());
		client.close();
	}

	/** 图像生成：URL 格式响应解析，全字段序列化。 */
	@Test
	public void testImageGeneration() {
		handle(200, "{\"created\":1700000000,\"data\":[{\"url\":\"https://example.com/a.png\","
			+ "\"revised_prompt\":\"a cat\"}]}");
		OpenAiCompatClient client = newClient();
		ImageResponse resp = client.generate(ImageRequest.builder()
			.model("dall-e-3").prompt("a cat").n(1).size("1024x1024")
			.quality("hd").style("vivid").responseFormat("url").user("tester").build());
		assertTrue(this.lastBody.get().contains("\"model\":\"dall-e-3\""));
		assertTrue(this.lastBody.get().contains("\"prompt\":\"a cat\""));
		assertTrue(this.lastBody.get().contains("\"n\":1"));
		assertTrue(this.lastBody.get().contains("\"size\":\"1024x1024\""));
		assertTrue(this.lastBody.get().contains("\"quality\":\"hd\""));
		assertTrue(this.lastBody.get().contains("\"style\":\"vivid\""));
		assertTrue(this.lastBody.get().contains("\"response_format\":\"url\""));
		assertEquals(1700000000L, resp.created());
		assertEquals(1, resp.data().size());
		assertEquals("https://example.com/a.png", resp.firstUrl());
		assertEquals("a cat", resp.data().get(0).revisedPrompt());
		client.close();
	}

	/** 图像生成：b64_json 格式 + 便捷 generate(model,prompt)。 */
	@Test
	public void testImageGenerationB64() {
		handle(200, "{\"created\":1700000001,\"data\":[{\"b64_json\":\"iVBORw0KGgo=\"}]}");
		OpenAiCompatClient client = newClient();
		ImageResponse resp = client.generate("dall-e-2", "draw");
		assertEquals("iVBORw0KGgo=", resp.firstB64());
		assertNull(resp.firstUrl());
		client.close();
	}

	/** 图像生成：空 data 响应不抛异常。 */
	@Test
	public void testImageGenerationEmptyData() {
		handle(200, "{\"created\":0}");
		OpenAiCompatClient client = newClient();
		ImageResponse resp = client.generate("dall-e-3", "x");
		assertTrue(resp.data().isEmpty());
		assertNull(resp.firstUrl());
		client.close();
	}

	/** 图像生成：extra 透传字段。 */
	@Test
	public void testImageGenerationExtra() {
		handle(200, "{\"created\":1,\"data\":[{\"url\":\"http://x/y.png\"}]}");
		OpenAiCompatClient client = newClient();
		client.generate(ImageRequest.builder().model("m").prompt("p")
			.extra("custom_field", "custom_value").build());
		assertTrue(this.lastBody.get().contains("\"custom_field\":\"custom_value\""));
		client.close();
	}

	/** doGet：GET 请求鉴权与响应解析。 */
	@Test
	public void testDoGet() {
		handle(200, "{\"status\":\"SUCCEEDED\",\"result\":\"ok\"}");
		TestGetClient client = new TestGetClient(AiConfig.builder()
			.apiKey("test-key").baseUrl(this.baseUrl).build());
		JsonObject resp = client.doGetPublic("/tasks/123");
		assertEquals("SUCCEEDED", resp.getString("status"));
		assertEquals("Bearer test-key", this.lastAuth.get());
		client.close();
	}

	/** doGet：404 映射为 AiApiException。 */
	@Test
	public void testDoGetNotFound() {
		handle(404, "{\"error\":\"not found\"}");
		TestGetClient client = new TestGetClient(AiConfig.builder()
			.apiKey("test-key").baseUrl(this.baseUrl).build());
		assertThrows(AiApiException.class, () -> client.doGetPublic("/tasks/missing"));
		client.close();
	}

	/** 暴露 protected doGet 的测试子类。 */
	private static class TestGetClient extends OpenAiCompatClient {
		TestGetClient(AiConfig config) {
			super(config);
		}
		JsonObject doGetPublic(String path) {
			return doGet(path);
		}
	}

	// ==================== 视频生成 ====================

	/** 视频生成：异步轮询 queued → in_progress → completed，解析 data[0].url。 */
	@Test
	public void testVideoGeneration() {
		AtomicInteger pollCount = new AtomicInteger(0);
		AtomicReference<String> submitBody = new AtomicReference<>();
		handle(ex -> {
			String method = ex.getRequestMethod();
			String uri = ex.getRequestURI().toString();
			if ("POST".equals(method) && uri.endsWith("/videos")) {
				submitBody.set(this.lastBody.get());
				respond(ex, 200, "{\"id\":\"v-1\",\"status\":\"queued\",\"created_at\":100}");
			} else if ("GET".equals(method) && uri.contains("/videos/v-1")) {
				int n = pollCount.incrementAndGet();
				if (n == 1) {
					respond(ex, 200, "{\"id\":\"v-1\",\"status\":\"in_progress\",\"progress\":50}");
				} else {
					respond(ex, 200, "{\"id\":\"v-1\",\"status\":\"completed\",\"created_at\":100,"
						+ "\"data\":[{\"url\":\"https://cdn.example.com/v.mp4\"}]}");
				}
			} else {
				respond(ex, 404, "{}");
			}
		});
		FastVideoClient client = new FastVideoClient(AiConfig.builder()
			.apiKey("k").baseUrl(this.baseUrl).build());
		VideoResponse resp = client.generate(VideoRequest.builder()
			.model("sora-2").prompt("a cat").duration(5).size("1280x720").build());
		assertTrue(submitBody.get().contains("\"model\":\"sora-2\""));
		assertTrue(submitBody.get().contains("\"prompt\":\"a cat\""));
		assertTrue(submitBody.get().contains("\"seconds\":5"));
		assertEquals("https://cdn.example.com/v.mp4", resp.firstUrl());
		assertEquals(100L, resp.created());
		assertEquals(2, pollCount.get());
		client.close();
	}

	/** 视频生成：failed 状态抛 AiException。 */
	@Test
	public void testVideoGenerationFailed() {
		handle(ex -> {
			String method = ex.getRequestMethod();
			if ("POST".equals(method)) {
				respond(ex, 200, "{\"id\":\"v-2\",\"status\":\"queued\"}");
			} else {
				respond(ex, 200, "{\"id\":\"v-2\",\"status\":\"failed\",\"failure_reason\":\"nsfw\"}");
			}
		});
		FastVideoClient client = new FastVideoClient(AiConfig.builder()
			.apiKey("k").baseUrl(this.baseUrl).build());
		assertThrows(com.sure.ai.exception.AiException.class, () ->
			client.generate(VideoRequest.of("sora-2", "bad")));
		client.close();
	}

	/** 视频生成：提交响应无 id 抛 AiException。 */
	@Test
	public void testVideoGenerationNoId() {
		handle(200, "{\"status\":\"queued\"}");
		FastVideoClient client = new FastVideoClient(AiConfig.builder()
			.apiKey("k").baseUrl(this.baseUrl).build());
		assertThrows(com.sure.ai.exception.AiException.class, () ->
			client.generate(VideoRequest.of("sora-2", "x")));
		client.close();
	}

	/** 调小轮询间隔的视频客户端子类。 */
	private static class FastVideoClient extends OpenAiCompatClient {
		FastVideoClient(AiConfig config) {
			super(config);
			this.videoPollIntervalMs = 10L;
			this.videoMaxWaitMs = 5000L;
		}
	}

	// ==================== TTS ====================

	/** TTS：POST JSON → 二进制音频响应。 */
	@Test
	public void testTts() {
		byte[] fakeAudio = new byte[]{0x49, 0x44, 0x33, 0x04};
		handle(ex -> {
			ex.getResponseHeaders().set("Content-Type", "audio/mpeg");
			ex.sendResponseHeaders(200, fakeAudio.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(fakeAudio);
			}
		});
		OpenAiCompatClient client = newClient();
		TtsResponse resp = client.synthesize(TtsRequest.builder()
			.model("tts-1").input("hello").voice("alloy")
			.responseFormat("mp3").speed(1.5).build());
		assertTrue(this.lastBody.get().contains("\"model\":\"tts-1\""));
		assertTrue(this.lastBody.get().contains("\"input\":\"hello\""));
		assertTrue(this.lastBody.get().contains("\"voice\":\"alloy\""));
		assertTrue(this.lastBody.get().contains("\"speed\":1.5"));
		assertEquals(fakeAudio.length, resp.audioLength());
		assertEquals("mp3", resp.format());
		client.close();
	}

	/** TTS：便捷 synthesize(model, text, voice)。 */
	@Test
	public void testTtsConvenience() {
		byte[] audio = new byte[]{1, 2, 3};
		handle(ex -> {
			ex.getResponseHeaders().set("Content-Type", "audio/wav");
			ex.sendResponseHeaders(200, audio.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(audio);
			}
		});
		OpenAiCompatClient client = newClient();
		TtsResponse resp = client.synthesize("tts-1-hd", "hi", "nova");
		assertEquals(3, resp.audioLength());
		client.close();
	}

	// ==================== STT ====================

	/** STT：multipart/form-data 上传 → JSON 文本响应。 */
	@Test
	public void testStt() {
		handle(200, "{\"text\":\"hello world\",\"language\":\"en\",\"duration\":2.5,"
			+ "\"segments\":[{\"id\":0,\"start\":0.0,\"end\":2.5,\"text\":\"hello world\"}],"
			+ "\"words\":[{\"word\":\"hello\",\"start\":0.0,\"end\":1.0}]}");
		OpenAiCompatClient client = newClient();
		byte[] audio = "fake-audio".getBytes(StandardCharsets.UTF_8);
		SttResponse resp = client.transcribe(SttRequest.builder()
			.model("whisper-1").audioData(audio).fileName("test.mp3")
			.contentType("audio/mpeg").language("en").build());
		// 请求体为 multipart，含 boundary 与字段
		assertTrue(this.lastBody.get().contains("------sureai"));
		assertTrue(this.lastBody.get().contains("whisper-1"));
		assertEquals("hello world", resp.text());
		assertEquals("en", resp.language());
		assertEquals(Double.valueOf(2.5), resp.duration());
		assertEquals(1, resp.segments().size());
		assertEquals(1, resp.words().size());
		client.close();
	}

	/** STT：便捷 transcribe(model, audioData)。 */
	@Test
	public void testSttConvenience() {
		handle(200, "{\"text\":\"transcribed text\"}");
		OpenAiCompatClient client = newClient();
		SttResponse resp = client.transcribe("gpt-4o-transcribe", new byte[]{1, 2});
		assertEquals("transcribed text", resp.text());
		assertTrue(resp.segments().isEmpty());
		client.close();
	}

	/** STT：verbose_json 含段级与词级时间戳。 */
	@Test
	public void testSttVerboseJson() {
		handle(200, "{\"text\":\"full text\",\"segments\":["
			+ "{\"id\":0,\"start\":0.0,\"end\":1.0,\"text\":\"part1\",\"words\":["
			+ "{\"word\":\"a\",\"start\":0.0,\"end\":0.5},{\"word\":\"b\",\"start\":0.5,\"end\":1.0}]},"
			+ "{\"id\":1,\"start\":1.0,\"end\":2.0,\"text\":\"part2\"}],"
			+ "\"words\":[{\"word\":\"a\",\"start\":0.0,\"end\":0.5}]}");
		OpenAiCompatClient client = newClient();
		SttResponse resp = client.transcribe(SttRequest.of("whisper-1", new byte[]{1}));
		assertEquals("full text", resp.text());
		assertEquals(2, resp.segments().size());
		assertEquals(2, resp.segments().get(0).words().size());
		assertEquals("a", resp.segments().get(0).words().get(0).word());
		assertEquals(1, resp.words().size());
		client.close();
	}

	// ==================== P1：responseFormat / DocumentPart / 缓存控制 ====================

	/** responseFormat 字符串形式：序列化为 response_format 字段。 */
	@Test
	public void testResponseFormatString() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("q"))
			.responseFormat("json_object").build());
		assertTrue(this.lastBody.get().contains("\"response_format\":\"json_object\""));
		client.close();
	}

	/** responseFormat 对象形式（JSON Schema）：序列化为嵌套对象。 */
	@Test
	public void testResponseFormatSchema() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
		JsonObject schema = new JsonObject();
		schema.put("type", "json_schema");
		JsonObject inner = new JsonObject();
		inner.put("name", "answer");
		schema.set("json_schema", inner);
		OpenAiCompatClient client = newClient();
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("q"))
			.responseFormat(schema).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"response_format\""));
		assertTrue(body.contains("\"type\":\"json_schema\""));
		assertTrue(body.contains("\"answer\""));
		client.close();
	}

	/** 未设置 responseFormat 时请求体不含该字段。 */
	@Test
	public void testNoResponseFormatByDefault() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("q")).build());
		assertTrue(!this.lastBody.get().contains("response_format"));
		client.close();
	}

	/** DocumentPart：fileId 形式序列化为 input_file.file_id。 */
	@Test
	public void testDocumentPartFileId() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		List<MessagePart> parts = List.of(
			TextPart.of("阅读这份合同"), DocumentPart.ofFileId("file-abc"));
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user(parts)).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"type\":\"input_file\""));
		assertTrue(body.contains("\"file_id\":\"file-abc\""));
		client.close();
	}

	/** DocumentPart：base64 形式序列化为 input_file.file_data(data URI)。 */
	@Test
	public void testDocumentPartBase64() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		List<MessagePart> parts = List.of(
			TextPart.of("总结"), DocumentPart.ofBase64("doc.pdf", "application/pdf", "QkFTRTY0"));
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user(parts)).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"filename\":\"doc.pdf\""));
		assertTrue(body.contains("\"mime_type\":\"application/pdf\""));
		assertTrue(body.contains("data:application/pdf;base64,QkFTRTY0"));
		client.close();
	}

	/** TextPart 带缓存控制：序列化为 cache_control。 */
	@Test
	public void testTextPartCacheControl() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		List<MessagePart> parts = List.of(
			TextPart.ofWithCache("长系统提示词", CacheControl.ephemeral()),
			TextPart.of("普通文本"));
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user(parts)).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"cache_control\":{\"type\":\"ephemeral\"}"));
		// 普通文本片段不应带 cache_control
		assertTrue(body.indexOf("cache_control") < body.length() - 1);
		client.close();
	}

	// ==================== P2：reasoning / grounding ====================

	/** reasoning_effort 序列化为请求体字段。 */
	@Test
	public void testReasoningEffortSerialized() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		client.chat(ChatRequest.builder().model("o-series").messages(ChatMessage.user("q"))
			.reasoningEffort("high").build());
		assertTrue(this.lastBody.get().contains("\"reasoning_effort\":\"high\""));
		client.close();
	}

	/** 未设置 reasoningEffort 时请求体不含该字段。 */
	@Test
	public void testNoReasoningEffortByDefault() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("q")).build());
		assertTrue(!this.lastBody.get().contains("reasoning_effort"));
		client.close();
	}

	/** grounding 字符串 "web_search" 注入 web_search 工具。 */
	@Test
	public void testGroundingWebSearchInjected() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("今天天气"))
			.grounding("web_search").build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"tools\""));
		assertTrue(body.contains("\"type\":\"web_search\""));
		client.close();
	}

	/** grounding 自定义对象直接作为工具注入，并与已有 function 工具共存。 */
	@Test
	public void testGroundingCustomToolAppended() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		JsonObject webSearch = new JsonObject();
		webSearch.put("type", "web_search_preview");
		webSearch.put("search_context_size", "high");
		OpenAiCompatClient client = newClient();
		ToolFunction fn = ToolFunction.of("getWeather", "天气", "{}");
		client.chat(ChatRequest.builder().model("gpt").messages(ChatMessage.user("q"))
			.tools(List.of(ToolSpec.of(fn))).grounding(webSearch).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("getWeather"));
		assertTrue(body.contains("web_search_preview"));
		assertTrue(body.contains("search_context_size"));
		client.close();
	}

	/** reasoning_content 响应解析：写入 ChatMessage.reasoningContent。 */
	@Test
	public void testReasoningContentParsed() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"answer\","
			+ "\"reasoning_content\":\"thinking step\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("o-series")
			.messages(ChatMessage.user("q")).build());
		assertEquals("answer", resp.firstText());
		assertEquals("thinking step", resp.choices().get(0).message().reasoningContent());
		client.close();
	}

	/** grounding 来源解析：annotations.url_citation 提取为 GroundingSource。 */
	@Test
	public void testGroundingSourcesParsed() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,"
			+ "\"message\":{\"role\":\"assistant\",\"content\":\"见引用\","
			+ "\"annotations\":[{\"type\":\"url_citation\","
			+ "\"url_citation\":{\"url\":\"https://a.com\",\"title\":\"A 站\"},"
			+ "\"quoted_text\":\"引用片段\"}]},"
			+ "\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user("q")).grounding("web_search").build());
		List<GroundingSource> sources = resp.groundingSources();
		assertEquals(1, sources.size());
		assertEquals("A 站", sources.get(0).title());
		assertEquals("https://a.com", sources.get(0).url());
		assertEquals("引用片段", sources.get(0).content());
		client.close();
	}

	// ==================== P2：模型列表 / 审核 / 微调 ====================

	/** listModels：GET /models 解析 data[]。 */
	@Test
	public void testListModels() {
		handle(200, "{\"object\":\"list\",\"data\":["
			+ "{\"id\":\"gpt-4o\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"openai\"},"
			+ "{\"id\":\"gpt-4o-mini\",\"object\":\"model\",\"created\":1700000001,\"owned_by\":\"openai\"}]}");
		OpenAiCompatClient client = newClient();
		List<Model> models = client.listModels();
		assertEquals(2, models.size());
		assertEquals("gpt-4o", models.get(0).id());
		assertEquals(1700000000L, models.get(0).created().longValue());
		assertEquals("openai", models.get(0).ownedBy());
		assertEquals("model", models.get(0).object());
		client.close();
	}

	/** moderate：POST /moderations 解析 results。 */
	@Test
	public void testModerate() {
		handle(200, "{\"id\":\"mod-1\",\"model\":\"text-moderation-latest\","
			+ "\"results\":[{\"flagged\":true,"
			+ "\"categories\":{\"sexual\":false,\"violence\":true,\"hate\":false},"
			+ "\"category_scores\":{\"sexual\":0.01,\"violence\":0.99,\"hate\":0.05}}]}");
		OpenAiCompatClient client = newClient();
		ModerationResponse resp = client.moderate(ModerationRequest.of("暴力内容"));
		assertTrue(this.lastBody.get().contains("\"input\":\"暴力内容\""));
		assertTrue(this.lastBody.get().contains("\"model\":\"text-moderation-latest\""));
		assertEquals("mod-1", resp.id());
		assertTrue(resp.flagged());
		assertEquals(1, resp.results().size());
		assertTrue(resp.results().get(0).flagged());
		assertTrue(resp.results().get(0).categories().contains("violence"));
		assertEquals(0.99, resp.results().get(0).categoryScores().get("violence"), 1e-9);
		client.close();
	}

	/** createFineTune：POST /fine_tuning/jobs 解析任务。 */
	@Test
	public void testCreateFineTune() {
		handle(200, "{\"id\":\"ftjob-1\",\"status\":\"queued\",\"model\":\"gpt-4o-mini\","
			+ "\"created_at\":1000,\"training_file\":\"file-1\"}");
		OpenAiCompatClient client = newClient();
		FineTuneResponse resp = client.createFineTune(FineTuneRequest.builder()
			.model("gpt-4o-mini").trainingFileId("file-1").suffix("my").build());
		assertTrue(this.lastBody.get().contains("\"training_file\":\"file-1\""));
		assertTrue(this.lastBody.get().contains("\"model\":\"gpt-4o-mini\""));
		assertTrue(this.lastBody.get().contains("\"suffix\":\"my\""));
		assertEquals("ftjob-1", resp.id());
		assertEquals("queued", resp.status());
		assertEquals(1000L, resp.createdAt().longValue());
		assertFalse(resp.isCompleted());
		client.close();
	}

	/** getFineTune：GET /fine_tuning/jobs/{id} 解析完成状态与错误。 */
	@Test
	public void testGetFineTune() {
		handle(200, "{\"id\":\"ftjob-2\",\"status\":\"succeeded\",\"model\":\"gpt\","
			+ "\"fine_tuned_model\":\"ft-2024\",\"finished_at\":2000}");
		OpenAiCompatClient client = newClient();
		FineTuneResponse resp = client.getFineTune("ftjob-2");
		assertEquals("succeeded", resp.status());
		assertTrue(resp.isCompleted());
		assertEquals("ft-2024", resp.fineTunedModel());
		assertEquals(2000L, resp.completedAt().longValue());
		client.close();
	}

	/** uploadTrainingFile：multipart /files 返回 file_id。 */
	@Test
	public void testUploadTrainingFile() {
		handle(200, "{\"id\":\"file-9\",\"object\":\"file\",\"purpose\":\"fine-tune\"}");
		OpenAiCompatClient client = newClient();
		String id = client.uploadTrainingFile("train.jsonl", "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
		assertEquals("file-9", id);
		assertTrue(this.lastBody.get().contains("purpose"));
		client.close();
	}
}
