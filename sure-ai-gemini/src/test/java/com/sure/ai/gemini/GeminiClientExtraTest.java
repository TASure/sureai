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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.Role;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;

/**
 * {@link GeminiClient} 边界分支测试：图像 n/size、snake inline_data、多 system、stop、
 * 工具声明、responseFormat 各分支、函数结果包装、groundingAttribution、流式空分片、
 * 响应 functionCall 解析。
 *
 * @author sureai
 * @since 0.2.0
 */
public class GeminiClientExtraTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1beta";
		this.lastBody.set(null);
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造客户端。 */
	private GeminiClient newClient() {
		return new GeminiClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
	}

	/** 注册普通 JSON 处理器。 */
	private void handle(int status, String responseBody) {
		this.server.createContext("/", ex -> {
			this.lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(ex, status, responseBody);
		});
	}

	/** 发送 JSON。 */
	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 图像生成带 n 与 size → imageConfig 分支。 */
	@Test
	public void testImageWithNAndSize() {
		String b64 = "iVBORw0KGgoAAAANSUhEUg==";
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{"
			+ "\"mimeType\":\"image/png\",\"data\":\"" + b64 + "\"}}]}}]}");
		GeminiClient client = newClient();
		ImageResponse resp = client.generate(ImageRequest.builder()
			.model("gemini-2.0-flash-exp").prompt("cat").n(2).size("1024x1024").build());
		String body = this.lastBody.get();
		assertTrue(body, body.contains("\"numberOfImages\":2"));
		assertTrue(body, body.contains("\"imageSize\":\"1024x1024\""));
		assertTrue(body, body.contains("\"imageConfig\""));
		assertEquals(b64, resp.firstB64());
		client.close();
	}

	/** 响应中 snake_case inline_data 仍可解析。 */
	@Test
	public void testImageResponseSnakeInlineData() {
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"inline_data\":{"
			+ "\"data\":\"snake-b64\"}}]}}]}");
		GeminiClient client = newClient();
		ImageResponse resp = client.generate(
			ImageRequest.of("gemini-2.0-flash-exp", "cat"));
		assertEquals("snake-b64", resp.firstB64());
		client.close();
	}

	/** 多条 system 消息以换行拼接为 systemInstruction。 */
	@Test
	public void testMultipleSystemMessages() {
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
		GeminiClient client = newClient();
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.system("规则A"), ChatMessage.system("规则B"), ChatMessage.user("hi"))
			.build());
		String body = this.lastBody.get();
		assertTrue(body, body.contains("\"systemInstruction\""));
		assertTrue(body, body.contains("规则A"));
		assertTrue(body, body.contains("规则B"));
		client.close();
	}

	/** stop 序列映射为 stopSequences。 */
	@Test
	public void testStopSequences() {
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
		GeminiClient client = newClient();
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("hi")).stop(List.of("STOP", "END")).build());
		String body = this.lastBody.get();
		assertTrue(body, body.contains("\"stopSequences\""));
		assertTrue(body, body.contains("STOP"));
		client.close();
	}

	/** tools 非空 → function_declarations 声明（含 description/parameters）。 */
	@Test
	public void testToolsDeclaration() {
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
		GeminiClient client = newClient();
		ToolFunction fn = ToolFunction.of("get_weather", "查天气", "{\"type\":\"object\"}");
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("weather"))
			.tools(List.of(ToolSpec.of(fn))).build());
		String body = this.lastBody.get();
		assertTrue(body, body.contains("\"function_declarations\""));
		assertTrue(body, body.contains("\"get_weather\""));
		assertTrue(body, body.contains("\"description\":\"查天气\""));
		assertTrue(body, body.contains("\"parameters\""));
		client.close();
	}

	/** responseFormat 为 List（非对象）→ 直接返回不报错。 */
	@Test
	public void testResponseFormatList() {
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
		GeminiClient client = newClient();
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("hi")).responseFormat(List.of("a", "b")).build());
		assertNotNull(this.lastBody.get());
		client.close();
	}

	/** responseFormat 对象 type=json_object 但无 json_schema → 仅写 responseMimeType。 */
	@Test
	public void testResponseFormatObjectTypeJson() {
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
		GeminiClient client = newClient();
		JsonObject format = Json.object();
		format.put("type", "json_object");
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("hi")).responseFormat(format).build());
		String body = this.lastBody.get();
		assertTrue(body, body.contains("\"responseMimeType\":\"application/json\""));
		client.close();
	}

	/** 工具结果为纯文本（非 JSON）→ 包成 {"result": text}。 */
	@Test
	public void testToolResultNonJson() {
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
		GeminiClient client = newClient();
		ChatMessage result = ChatMessage.of(Role.TOOL, "plain text result", null, "get_weather", "call_1", null);
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("w"), result).build());
		String body = this.lastBody.get();
		assertTrue(body, body.contains("\"functionResponse\""));
		assertTrue(body, body.contains("\"result\":\"plain text result\""));
		client.close();
	}

	/** 工具结果为非法 JSON → 捕获解析异常后回退为包装对象。 */
	@Test
	public void testToolResultInvalidJson() {
		handle(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
		GeminiClient client = newClient();
		ChatMessage result = ChatMessage.of(Role.TOOL, "{broken json", null, "get_weather", "call_2", null);
		client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("w"), result).build());
		String body = this.lastBody.get();
		assertTrue(body, body.contains("\"result\":\"{broken json\""));
		client.close();
	}

	/** groundingMetadata.groundingAttribution（旧字段）解析为来源。 */
	@Test
	public void testGroundingAttribution() {
		String resp = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"结果\"}]},"
			+ "\"groundingMetadata\":{\"groundingAttribution\":["
			+ "{\"title\":\"来源X\",\"uri\":\"https://x.com\"},"
			+ "{\"title\":\"来源Y\",\"url\":\"https://y.com\"}]}}]}";
		handle(200, resp);
		GeminiClient client = newClient();
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("查")).grounding("web_search").build());
		assertEquals(2, result.groundingSources().size());
		assertEquals("来源X", result.groundingSources().get(0).title());
		assertEquals("https://y.com", result.groundingSources().get(1).url());
		client.close();
	}

	/** 流式分片无 candidates → 空分片。 */
	@Test
	public void testStreamChunkNoCandidates() {
		String sse = "data: {\"usageMetadata\":{\"totalTokenCount\":1}}\n\n";
		this.server.createContext("/", ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		GeminiClient client = newClient();
		final int[] count = new int[1];
		client.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			chunk -> count[0]++);
		assertEquals(1, count[0]);
		client.close();
	}

	/** 响应 candidate 含 functionCall 与 thought → 解析出工具调用与思考内容。 */
	@Test
	public void testResponseFunctionCallAndThought() {
		String resp = "{\"candidates\":[{\"content\":{\"parts\":["
			+ "{\"thought\":\"思考中\"},"
			+ "{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"SF\"}}}"
			+ "]},\"finishReason\":\"STOP\"}]}";
		handle(200, resp);
		GeminiClient client = newClient();
		ChatResponse result = client.chat(ChatRequest.builder().model("m")
			.messages(ChatMessage.user("w")).build());
		ChatMessage msg = result.choices().get(0).message();
		assertEquals("思考中", msg.reasoningContent());
		assertEquals(1, msg.toolCalls().size());
		assertEquals("get_weather", msg.toolCalls().get(0).name());
		assertTrue(msg.toolCalls().get(0).argumentsJson().contains("city"));
		assertNull(msg.content());
		client.close();
	}
}
