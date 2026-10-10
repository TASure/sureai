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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;
import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;
import com.sure.ai.model.ToolCall;
import com.sure.ai.model.VideoPart;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * 各 compat 能力域策略的请求构建与响应解析边界测试（本地 HttpServer）。
 *
 * @author sureai
 * @since 1.4.0
 */
public class CompatStrategyEdgeTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
	}

	@After
	public void tearDown() {
		this.server.stop(0);
	}

	private OpenAiCompatClient newClient() {
		return new OpenAiCompatClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
	}

	private void handle(int status, String body) {
		this.server.createContext("/", ex -> {
			this.lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] b = body.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(status, b.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(b);
			}
		});
	}

	// ==================== ChatCompatStrategy ====================

	/** 请求体：stop / toolChoice / extra / name / toolCallId / assistant tool_calls。 */
	@Test
	public void testChatRequestEdgeFields() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
			+ "\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		ChatMessage assistant = ChatMessage.assistant(List.of(
			ToolCall.of("call_1", "getWeather", "{\"x\":1}")));
		ChatMessage toolMsg = ChatMessage.tool("call_1", "sunny");
		ChatMessage named = ChatMessage.of(com.sure.ai.model.Role.USER, "q", null, "Alice", null,
			null);
		client.chat(ChatRequest.builder().model("gpt")
			.messages(assistant, toolMsg, named)
			.stop(List.of("\n"))
			.toolChoice("required")
			.extra("custom_flag", true)
			.build());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"stop\""));
		assertTrue(body.contains("\"tool_choice\":\"required\""));
		assertTrue(body.contains("\"custom_flag\":true"));
		assertTrue(body.contains("\"name\":\"Alice\""));
		assertTrue(body.contains("\"tool_call_id\":\"call_1\""));
		assertTrue(body.contains("\"tool_calls\""));
		client.close();
	}

	/** DocumentPart base64 无 mimeType 时回退 application/pdf。 */
	@Test
	public void testDocumentPartNoMimeType() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"ok\","
			+ "\"role\":\"assistant\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		DocumentPart dp = DocumentPart.ofBase64("doc.pdf", null, "QkFTRTY0");
		client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user(List.of(dp))).build());
		assertTrue(this.lastBody.get().contains("data:application/pdf;base64,QkFTRTY0"));
		client.close();
	}

	/** VideoPart 序列化为 video_url。 */
	@Test
	public void testVideoPartInRequest() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"ok\","
			+ "\"role\":\"assistant\"},\"finish_reason\":\"stop\"}]}");
		OpenAiCompatClient client = newClient();
		client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user(List.of(VideoPart.ofUrl("http://v/x.mp4")))).build());
		assertTrue(this.lastBody.get().contains("\"type\":\"video_url\""));
		client.close();
	}

	/** 响应：annotation 普通 url 类型 + 消息无 role / content 为 null。 */
	@Test
	public void testChatResponseAnnotationUrlAndNoRole() {
		handle(200, "{\"id\":\"c\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
			+ "\"message\":{\"annotations\":[{\"type\":\"url\",\"url\":\"https://a.com\","
			+ "\"title\":\"A\"}]}}]}");
		OpenAiCompatClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user("q")).build());
		assertEquals(1, resp.groundingSources().size());
		assertEquals("https://a.com", resp.groundingSources().get(0).url());
		assertNull(resp.choices().get(0).message().role());
		assertNull(resp.choices().get(0).message().content());
		client.close();
	}

	// ==================== StreamCompatStrategy ====================

	/** 流式：空 choices / delta 无 role / delta 带 tool_calls。 */
	@Test
	public void testStreamEdgeChunks() {
		String sse = "data: {\"id\":\"c\",\"choices\":[]}\n\n"
			+ "data: {\"choices\":[{\"index\":0,\"delta\":{}}]}\n\n"
			+ "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
			+ "{\"id\":\"call_1\",\"function\":{\"name\":\"w\",\"arguments\":\"{}\"}}]}}]}\n\n"
			+ "data: [DONE]\n\n";
		this.server.createContext("/", ex -> {
			byte[] b = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, b.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(b);
			}
		});
		OpenAiCompatClient client = newClient();
		client.chatStream(ChatRequest.builder().model("gpt").messages(ChatMessage.user("q")).build(),
			chunk -> {
				if (chunk.toolCalls() != null) {
					assertEquals("w", chunk.toolCalls().get(0).name());
				}
			});
		client.close();
	}

	// ==================== VideoCompatStrategy ====================

	/** 视频：withAudio / extra 请求字段 + video 对象响应分支。 */
	@Test
	public void testVideoWithAudioAndVideoObjectResponse() {
		AtomicInteger polls = new AtomicInteger();
		this.server.createContext("/", ex -> {
			String method = ex.getRequestMethod();
			String uri = ex.getRequestURI().toString();
			if ("POST".equals(method) && uri.endsWith("/videos")) {
				this.lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				respondJson(ex, 200, "{\"id\":\"v1\",\"status\":\"queued\"}");
			} else if ("GET".equals(method)) {
				int n = polls.incrementAndGet();
				if (n == 1) {
					respondJson(ex, 200, "{\"id\":\"v1\",\"status\":\"in_progress\"}");
				} else {
					respondJson(ex, 200, "{\"id\":\"v1\",\"status\":\"completed\","
						+ "\"video\":{\"url\":\"https://cdn/v.mp4\",\"cover_image_url\":\"https://cdn/c.jpg\"}}");
				}
			}
		});
		OpenAiCompatClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.builder().model("sora")
			.prompt("cat").withAudio(true).extra("ratio", "16:9").build());
		assertTrue(this.lastBody.get().contains("\"with_audio\":true"));
		assertTrue(this.lastBody.get().contains("\"ratio\":\"16:9\""));
		assertEquals("https://cdn/v.mp4", resp.firstUrl());
		client.close();
	}

	/** 视频：顶层 url 响应分支。 */
	@Test
	public void testVideoTopLevelUrl() {
		this.server.createContext("/", ex -> respondJson(ex, 200,
			"{\"id\":\"v\",\"status\":\"completed\",\"url\":\"https://cdn/top.mp4\"}"));
		OpenAiCompatClient client = newClient();
		VideoResponse resp = client.generate(VideoRequest.of("sora", "cat"));
		assertEquals("https://cdn/top.mp4", resp.firstUrl());
		client.close();
	}

	// ==================== FineTuneCompatStrategy ====================

	/** 微调：hyperparameters / extra 请求字段 + completed_at 与 error 响应解析。 */
	@Test
	public void testFineTuneEdge() {
		handle(200, "{\"id\":\"ft1\",\"status\":\"failed\",\"model\":\"gpt\","
			+ "\"completed_at\":2000,\"error\":{\"message\":\"boom\"}}");
		OpenAiCompatClient client = newClient();
		FineTuneResponse resp = client.createFineTune(FineTuneRequest.builder()
			.model("gpt").trainingFileId("file-1")
			.hyperparameters(Map.of("epochs", 3))
			.extra("tag", "x")
			.build());
		assertTrue(this.lastBody.get().contains("\"hyperparameters\""));
		assertTrue(this.lastBody.get().contains("\"tag\":\"x\""));
		assertEquals("boom", resp.error());
		assertEquals(2000L, resp.completedAt().longValue());
		client.close();
	}

	/** 上传训练文件响应缺 id 抛异常。 */
	@Test
	public void testUploadFileMissingId() {
		handle(200, "{\"object\":\"file\"}");
		OpenAiCompatClient client = newClient();
		assertThrows(AiException.class, () -> client.uploadTrainingFile("a.jsonl", "{}".getBytes()));
		client.close();
	}

	// ==================== ModerationCompatStrategy ====================

	/** 审核：extra 字段 + results 缺失 + flagged=false 分支。 */
	@Test
	public void testModerationEdge() {
		handle(200, "{\"id\":\"m\",\"model\":\"mod\",\"results\":["
			+ "{\"flagged\":false,\"categories\":{\"hate\":false},\"category_scores\":{\"hate\":0.1}}]}");
		OpenAiCompatClient client = newClient();
		ModerationResponse resp = client.moderate(ModerationRequest.builder()
			.model("mod").input("hello").extra("note", "n").build());
		assertTrue(this.lastBody.get().contains("\"note\":\"n\""));
		assertFalse(resp.results().get(0).flagged());
		assertTrue(resp.results().get(0).categories().isEmpty());
		client.close();
	}

	private static void respondJson(HttpExchange ex, int status, String body) throws IOException {
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(status, b.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(b);
		}
	}
}
