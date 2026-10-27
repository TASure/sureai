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

package com.sure.ai.bedrock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

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

import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;

/**
 * {@link BedrockClient} 集成测试：本地 {@link HttpServer} mock，零真实网络。
 *
 * @author sureai
 * @since 1.2.0
 */
public class BedrockClientTest {

	private HttpServer server;
	private String endpoint;
	private final AtomicReference<String> lastUri = new AtomicReference<>();
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastAmzDate = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	/** 启动本地 mock。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.endpoint = "http://127.0.0.1:" + port;
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造指向 mock 的客户端。 */
	private BedrockClient newClient() {
		return new BedrockClient("AKID", "secret", null, "us-east-1",
			BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET, this.endpoint);
	}

	/** 注册固定 JSON 响应处理器。 */
	private void handle(int status, String body) {
		register(ex -> respond(ex, status, body));
	}

	/** 注册自定义处理器。 */
	private void register(Handler h) {
		this.server.createContext("/", exchange -> {
			this.lastUri.set(exchange.getRequestURI().toString());
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			this.lastAmzDate.set(exchange.getRequestHeaders().getFirst("X-Amz-Date"));
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

	/** 非流式 Converse：路径、签名头、请求体与响应映射。 */
	@Test
	public void testChatConverse() {
		handle(200, "{\"output\":{\"message\":{\"role\":\"assistant\","
			+ "\"content\":[{\"text\":\"你好\"}]}},\"stopReason\":\"end_turn\","
			+ "\"usage\":{\"inputTokens\":10,\"outputTokens\":5,\"totalTokens\":15}}");
		BedrockClient client = newClient();
		ChatResponse resp = client.chat(ChatRequest.builder()
			.model(BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET)
			.messages(List.of(ChatMessage.user("hi")))
			.maxTokens(128)
			.temperature(0.7)
			.build());

		assertTrue(this.lastUri.get().startsWith(
			"/model/anthropic.claude-3-5-sonnet-20240620-v1:0/converse"));
		assertNotNull(this.lastAuth.get());
		assertTrue(this.lastAuth.get().startsWith("AWS4-HMAC-SHA256 Credential=AKID/"));
		assertTrue(this.lastAuth.get().contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"));
		assertNotNull(this.lastAmzDate.get());
		assertTrue(this.lastAmzDate.get().matches("\\d{8}T\\d{6}Z"));
		assertTrue(this.lastBody.get().contains("\"role\":\"user\""));
		assertTrue(this.lastBody.get().contains("\"maxTokens\":128"));
		assertTrue(this.lastBody.get().contains("\"temperature\":0.7"));

		assertEquals("你好", resp.firstText());
		assertEquals("end_turn", resp.choices().get(0).finishReason());
		assertEquals(15, resp.usage().totalTokens());
		assertEquals(10, resp.usage().promptTokens());
		assertEquals(5, resp.usage().completionTokens());
		client.close();
	}

	/** system 消息映射到请求体 system 数组。 */
	@Test
	public void testChatSystemPromptMapped() {
		handle(200, "{\"output\":{\"message\":{\"content\":[{\"text\":\"ok\"}]}}}");
		BedrockClient client = newClient();
		client.chat(ChatRequest.builder().model(BedrockModels.AMAZON_NOVA_PRO)
			.messages(List.of(ChatMessage.system("you are helpful"), ChatMessage.user("hi"))).build());
		assertTrue(this.lastBody.get().contains("\"system\""));
		assertTrue(this.lastBody.get().contains("you are helpful"));
		assertTrue(this.lastUri.get().startsWith("/model/amazon.nova-pro-v1:0/converse"));
		client.close();
	}

	/** 流式 Converse：delta.text 与 stopReason 映射。 */
	@Test
	public void testChatStream() {
		String sse = "event: messageStart\n"
			+ "data: {\"eventType\":\"messageStart\",\"message\":{\"role\":\"assistant\"}}\n\n"
			+ "event: contentBlockDelta\n"
			+ "data: {\"eventType\":\"contentBlockDelta\",\"delta\":{\"text\":\"你\"}}\n\n"
			+ "event: contentBlockDelta\n"
			+ "data: {\"eventType\":\"contentBlockDelta\",\"delta\":{\"text\":\"好\"}}\n\n"
			+ "event: messageStop\n"
			+ "data: {\"eventType\":\"messageStop\",\"stopReason\":\"end_turn\"}\n\n"
			+ "event: metadata\n"
			+ "data: {\"eventType\":\"metadata\",\"usage\":{\"inputTokens\":3,\"outputTokens\":2,\"totalTokens\":5}}\n\n";
		register(ex -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/event-stream");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		});
		BedrockClient client = newClient();
		StringBuilder sb = new StringBuilder();
		final String[] finish = new String[1];
		client.chatStream(ChatRequest.builder().model(BedrockModels.META_LLAMA3_70B)
			.messages(List.of(ChatMessage.user("hi"))).build(),
			new java.util.function.Consumer<ChatStreamChunk>() {
				@Override
				public void accept(ChatStreamChunk chunk) {
					if (chunk.deltaText() != null) {
						sb.append(chunk.deltaText());
					}
					if (chunk.finishReason() != null) {
						finish[0] = chunk.finishReason();
					}
				}
			});
		assertEquals("你好", sb.toString());
		assertEquals("end_turn", finish[0]);
		assertTrue(this.lastUri.get().endsWith("/converse-stream"));
		client.close();
	}

	/** 403 映射为 AiAuthException。 */
	@Test
	public void testAuthFailure() {
		handle(403, "{\"message\":\"not authorized\"}");
		BedrockClient client = newClient();
		assertThrows(AiAuthException.class, () -> client.chat(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 非 2xx 错误映射为 AiException。 */
	@Test
	public void testServerError() {
		handle(500, "{\"message\":\"boom\"}");
		BedrockClient client = newClient();
		assertThrows(AiException.class, () -> client.chat(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build()));
		client.close();
	}

	/** 基本元信息。 */
	@Test
	public void testNameAndDefaults() {
		BedrockClient client = newClient();
		assertEquals("bedrock", client.name());
		assertTrue(this.lastUri.get() == null);
		client.close();

		// 默认端点（无 override）拼出 bedrock-runtime 地址。
		BedrockClient defaultClient = new BedrockClient("AK", "SK", null, "us-east-1", "m");
		assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com", defaultClient.endpoint());
		assertEquals("bedrock-runtime.us-east-1.amazonaws.com", defaultClient.host());
		defaultClient.close();
	}

	/** 缺 region 构造抛异常。 */
	@Test
	public void testBlankRegionThrows() {
		assertThrows(AiException.class,
			() -> new BedrockClient("AK", "SK", null, " ", "m"));
	}

	/** embedding 占位抛异常。 */
	@Test
	public void testEmbedNotSupported() {
		BedrockClient client = newClient();
		assertThrows(AiException.class, client::embedNotSupported);
		client.close();
	}
}
