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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.Model;
import com.sure.ai.model.TextPart;

/**
 * {@link GeminiClient} 集成测试：本地 HttpServer mock。
 *
 * @author sureai
 * @since 0.1.0
 */
public class GeminiClientTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastQuery = new AtomicReference<>();
	private final AtomicReference<String> lastPath = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/v1beta";
		this.lastBody.set(null);
		this.lastQuery.set(null);
		this.lastPath.set(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private GeminiClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey("gemini-key-123").baseUrl(this.baseUrl).build();
		return new GeminiClient(cfg);
	}

	/** 注册处理器。 */
	private void handle(int status, String responseBody) {
		this.server.createContext("/", exchange -> {
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastQuery.set(exchange.getRequestURI().getQuery());
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			respond(exchange, status, responseBody);
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

	/** 校验 key 查询参数 + generateContent 路径。 */
	@Test
	public void testKeyQueryParam() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"hi\"}]},\"finishReason\":\"STOP\"}],"
			+ "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"totalTokenCount\":5}}");
		GeminiClient client = newClient();
		client.chat(ChatRequest.builder().model("gemini-2.5-flash")
			.messages(ChatMessage.user("hello")).build());
		assertTrue("URL should contain ?key=", this.lastQuery.get().contains("key=gemini-key-123"));
		assertTrue("path should end with :generateContent",
			this.lastPath.get().endsWith(":generateContent"));
		client.close();
	}

	/** 请求体格式：contents + systemInstruction + generationConfig。 */
	@Test
	public void testRequestBodyFormat() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}");
		GeminiClient client = newClient();
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.system("Be concise."), ChatMessage.user("hi"))
			.temperature(0.5).maxTokens(256).topP(0.9).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"contents\""));
		assertTrue(body.contains("\"systemInstruction\""));
		assertTrue(body.contains("Be concise."));
		assertTrue(body.contains("\"generationConfig\""));
		assertTrue(body.contains("\"temperature\":0.5"));
		assertTrue(body.contains("\"maxOutputTokens\":256"));
		assertTrue(body.contains("\"topP\":0.9"));
		client.close();
	}

	/** 非流式响应解析。 */
	@Test
	public void testNonStreamParse() {
		String resp = "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"The answer is 42\"}]},\"finishReason\":\"STOP\"}],"
			+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":7,\"totalTokenCount\":17}}";
		handle(200, resp);
		GeminiClient client = newClient();
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("q")).build());
		assertEquals("The answer is 42", result.firstText());
		assertEquals("STOP", result.choices().get(0).finishReason());
		assertEquals(10, result.usage().promptTokens());
		assertEquals(7, result.usage().completionTokens());
		assertEquals(17, result.usage().totalTokens());
		client.close();
	}

	/** SSE 流式聚合：每个 data 是完整 JSON 对象。 */
	@Test
	public void testStreamSse() {
		String sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"Hel\"}]},\"index\":0}]}\n\n"
			+ "data: {\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"lo\"}]},\"index\":0}]}\n\n"
			+ "data: {\"candidates\":[{\"content\":{\"role\":\"model\"},"
			+ "\"finishReason\":\"STOP\",\"index\":0}]}\n\n";
		this.server.createContext("/", exchange -> {
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastQuery.set(exchange.getRequestURI().getQuery());
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		GeminiClient client = newClient();
		StringBuilder sb = new StringBuilder();
		String[] finish = new String[1];
		client.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
				if (chunk.finishReason() != null) {
					finish[0] = chunk.finishReason();
				}
			});
		assertEquals("Hello", sb.toString());
		assertEquals("STOP", finish[0]);
		assertTrue("stream path should contain alt=sse",
			this.lastQuery.get().contains("alt=sse"));
		assertTrue("key in query", this.lastQuery.get().contains("key="));
		client.close();
	}

	/** embedContent 向量请求。 */
	@Test
	public void testEmbed() {
		handle(200, "{\"embedding\":{\"values\":[0.1,0.2,0.3]}}");
		GeminiClient client = newClient();
		EmbeddingResponse resp = client.embed(new EmbeddingRequest("gemini-embedding-001", List.of("hello")));
		assertTrue("path should end with :embedContent",
			this.lastPath.get().endsWith(":embedContent"));
		assertTrue("body should contain content.parts", this.lastBody.get().contains("\"content\""));
		assertTrue(this.lastBody.get().contains("hello"));
		assertEquals(1, resp.embeddings().size());
		assertEquals(3, resp.embeddings().get(0).length, 0);
		assertEquals(0.2f, resp.embeddings().get(0)[1], 1e-6);
		client.close();
	}

	/** 400 错误映射为 AiApiException。 */
	@Test
	public void testBadRequest400() {
		handle(400, "{\"error\":{\"code\":400,\"message\":\"Invalid model\"}}");
		GeminiClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chat(
			ChatRequest.builder().model("bad-model").messages(ChatMessage.user("hi")).build()));
		assertEquals(400, e.getHttpStatus());
		assertTrue(e.getRawBody().contains("Invalid model"));
		client.close();
	}

	/** 多模态：ImagePart 映射为 camelCase inlineData（裸 base64）。 */
	@Test
	public void testMultimodalImage() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"seen\"}]},\"finishReason\":\"STOP\"}]}");
		GeminiClient client = newClient();
		List<MessagePart> parts = List.of(TextPart.of("describe"), ImagePart.ofBase64("aGVsbG8=", "image/png"));
		client.chat(ChatRequest.builder().model("m").messages(ChatMessage.user(parts)).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"inlineData\""));
		assertTrue(body.contains("\"mimeType\":\"image/png\""));
		assertTrue(body.contains("aGVsbG8="));
		assertFalse("must not use snake_case inline_data", body.contains("\"inline_data\""));
		client.close();
	}

	/** PDF 文档：DocumentPart 映射为 inlineData（与图片同结构）。 */
	@Test
	public void testPdfInput() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"summarized\"}]},\"finishReason\":\"STOP\"}]}");
		GeminiClient client = newClient();
		List<MessagePart> parts = List.of(
			TextPart.of("summarize"),
			DocumentPart.ofBase64("contract.pdf", "application/pdf", "UE9EXZEYg=="));
		client.chat(ChatRequest.builder().model("m").messages(ChatMessage.user(parts)).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"inlineData\""));
		assertTrue(body.contains("\"mimeType\":\"application/pdf\""));
		assertTrue(body.contains("UE9EXZEYg=="));
		client.close();
	}

	/** 结构化输出：responseFormat="json_object" 写入 generationConfig.responseMimeType。 */
	@Test
	public void testStructuredOutputString() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"{}\"}]},\"finishReason\":\"STOP\"}]}");
		GeminiClient client = newClient();
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("give json"))
			.responseFormat("json_object").build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"responseMimeType\":\"application/json\""));
		client.close();
	}

	/** 结构化输出：json_schema 对象提取 schema 写入 responseSchema。 */
	@Test
	public void testStructuredOutputSchema() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"{}\"}]},\"finishReason\":\"STOP\"}]}");
		GeminiClient client = newClient();
		JsonObject schema = Json.object();
		schema.put("type", "object");
		JsonObject props = Json.object();
		JsonObject answer = Json.object();
		answer.put("type", "string");
		props.put("answer", answer);
		schema.put("properties", props);
		JsonObject jsonSchema = Json.object();
		jsonSchema.put("name", "answer");
		jsonSchema.set("schema", schema);
		JsonObject format = Json.object();
		format.put("type", "json_schema");
		format.set("json_schema", jsonSchema);
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("give json"))
			.responseFormat(format).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"responseMimeType\":\"application/json\""));
		assertTrue(body.contains("\"responseSchema\""));
		assertTrue(body.contains("\"answer\""));
		client.close();
	}

	/** Prompt 缓存：extra("cachedContent", ...) 作为顶级字段透传。 */
	@Test
	public void testPromptCache() {
		handle(200, "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}");
		GeminiClient client = newClient();
		JsonObject cached = Json.object();
		cached.put("name", "cachedContents/abc123");
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("hi"))
			.extra("cachedContent", cached).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"cachedContent\""));
		assertTrue(body.contains("cachedContents/abc123"));
		client.close();
	}

	/** 图像生成：请求体含 responseModalities，响应提取 inlineData。 */
	@Test
	public void testImageGeneration() {
		String b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
		String resp = "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + b64 + "\"}}]},"
			+ "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"totalTokenCount\":100}}";
		handle(200, resp);
		GeminiClient client = newClient();
		ImageResponse result = client.generate(
			ImageRequest.of(GeminiModels.GEMINI_2_0_FLASH_EXP, "a cat"));
		String body = this.lastBody.get();
		assertTrue("body should set responseModalities",
			body.contains("\"responseModalities\""));
		assertTrue("body should request IMAGE modality", body.contains("\"IMAGE\""));
		assertTrue("path should end with :generateContent",
			this.lastPath.get().endsWith(":generateContent"));
		assertEquals("base64 should match", b64, result.firstB64());
		assertEquals(1, result.data().size());
		assertNotNull(result.rawJson());
		client.close();
	}

	/** 模型拒绝生成（仅 text part）：data 列表为空。 */
	@Test
	public void testImageGenerationNoImage() {
		String resp = "{\"candidates\":[{\"content\":{\"role\":\"model\","
			+ "\"parts\":[{\"text\":\"I cannot generate images for this request.\"}]},"
			+ "\"finishReason\":\"SAFETY\"}]}";
		handle(200, resp);
		GeminiClient client = newClient();
		ImageResponse result = client.generate(
			ImageRequest.of(GeminiModels.GEMINI_2_0_FLASH_EXP, "bad prompt"));
		assertTrue("no image part -> empty data", result.data().isEmpty());
		assertNull(result.firstB64());
		client.close();
	}

	/** 思考模式：thinkingConfig 写入 generationConfig.thinkingConfig，thought 解析为 reasoningContent。 */
	@Test
	public void testThinkingConfig() {
		String resp = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
			+ "{\"thought\":\"推理过程\"},{\"text\":\"答案\"}]},\"finishReason\":\"STOP\"}]}";
		handle(200, resp);
		GeminiClient client = newClient();
		JsonObject thinking = Json.object();
		thinking.put("thinkingBudget", 8192);
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("q"))
			.thinkingConfig(thinking).build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"thinkingConfig\""));
		assertTrue(body.contains("\"thinkingBudget\":8192"));
		assertEquals("答案", result.firstText());
		assertEquals("推理过程", result.choices().get(0).message().reasoningContent());
		client.close();
	}

	/** Grounding：grounding 非空注入 googleSearch 工具，groundingMetadata 解析为来源。 */
	@Test
	public void testGrounding() {
		String resp = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
			+ "{\"text\":\"结果\"}]},\"finishReason\":\"STOP\",\"groundingMetadata\":{"
			+ "\"groundingChunks\":[{\"web\":{\"title\":\"来源A\",\"uri\":\"https://a.com\"}},"
			+ "{\"web\":{\"title\":\"来源B\",\"uri\":\"https://b.com\"}}]}}]}";
		handle(200, resp);
		GeminiClient client = newClient();
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("查一下"))
			.grounding("web_search").build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"googleSearch\""));
		assertEquals(2, result.groundingSources().size());
		assertEquals("来源A", result.groundingSources().get(0).title());
		assertEquals("https://b.com", result.groundingSources().get(1).url());
		client.close();
	}

	/** 模型列表：GET models，name 去掉 models/ 前缀。 */
	@Test
	public void testListModels() {
		String resp = "{\"models\":[{\"name\":\"models/gemini-2.5-flash\","
			+ "\"baseModelId\":\"gemini-2.5\"},{\"name\":\"models/gemini-2.5-pro\"}]}";
		handle(200, resp);
		GeminiClient client = newClient();
		List<Model> models = client.listModels();
		assertTrue(this.lastPath.get().endsWith("/models"));
		assertEquals(2, models.size());
		assertEquals("gemini-2.5-flash", models.get(0).id());
		assertEquals("gemini-2.5", models.get(0).ownedBy());
		assertEquals("gemini-2.5-pro", models.get(1).id());
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("gemini", newClient().name());
	}
}
