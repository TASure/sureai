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

package com.sure.ai.ollama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
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
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

/**
 * {@link OllamaClient} 边界分支测试：请求体 stop/extra、流式错误/中断/截断、resolveUrl 拼接。
 *
 * <p>零真实网络：全部本地回环；错误路径通过关闭端口、慢响应、截断报文触发。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class OllamaClientExtraTest {

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> capturedBody = new AtomicReference<>();

	/** 启动本地 mock。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.capturedBody.set(null);
	}

	/** 停止 mock。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 构造指向 mock 的客户端。 */
	private OllamaClient newClient() {
		return new OllamaClient(AiConfig.builder().apiKey("x").baseUrl(this.baseUrl).build());
	}

	/** 发送 JSON 响应。 */
	private static void respondJson(HttpExchange ex, int status, String body) throws IOException {
		byte[] out = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	/** stop 与 extra 字段进入请求体 options/顶层。 */
	@Test
	public void testChatBodyStopAndExtra() {
		this.server.createContext("/", ex -> {
			byte[] in = ex.getRequestBody().readAllBytes();
			this.capturedBody.set(new String(in, StandardCharsets.UTF_8));
			respondJson(ex, 200, "{\"model\":\"m\",\"message\":{\"content\":\"ok\"},\"done\":true}");
		});
		OllamaClient client = newClient();
		client.chat(ChatRequest.builder().model("m").messages(ChatMessage.user("hi"))
			.stop(List.of("STOP")).extra("custom_flag", true).build());
		String body = this.capturedBody.get();
		assertTrue(body, body.contains("\"stop\""));
		assertTrue(body, body.contains("\"custom_flag\":true"));
		client.close();
	}

	/** 流式请求 IO 失败（连接拒绝）→ AiException(stream request failed)。 */
	@Test
	public void testStreamIOException() {
		OllamaClient dead = new OllamaClient(
			AiConfig.builder().apiKey("x").baseUrl("http://127.0.0.1:9").build());
		AiException e = assertThrows(AiException.class, () -> dead.chatStream(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(), c -> { }));
		assertTrue(e.getMessage(), e.getMessage().contains("stream request failed"));
		dead.close();
	}

	/** 流式请求被中断 → AiException(stream interrupted)。 */
	@Test
	public void testStreamInterrupted() throws Exception {
		this.server.createContext("/", ex -> {
			try {
				Thread.sleep(2000L);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			respondJson(ex, 200, "{\"model\":\"m\",\"message\":{\"content\":\"x\"},\"done\":true}");
		});
		OllamaClient client = newClient();
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
		AiException e = assertThrows(AiException.class, () -> client.chatStream(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(), c -> { }));
		assertTrue(e.getMessage(), e.getMessage().contains("stream interrupted"));
		watcher.join();
		Thread.interrupted();
		client.close();
	}

	/** 非 2xx 响应：完整错误体被读取并经 mapError 映射为 AiApiException。 */
	@Test
	public void testStreamNon2xx() {
		this.server.createContext("/", ex -> {
			ex.getResponseHeaders().set("Content-Type", "application/json");
			byte[] out = "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(500, out.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(out);
			}
		});
		OllamaClient client = newClient();
		com.sure.ai.exception.AiApiException e = assertThrows(
			com.sure.ai.exception.AiApiException.class, () -> client.chatStream(
				ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(), c -> { }));
		assertEquals(500, e.getHttpStatus());
		assertTrue(e.getRawBody().contains("model not found"));
		client.close();
	}

	/** NDJSON 中夹空行应被跳过（完整报文）；正常行聚合文本。 */
	@Test
	public void testStreamNdjsonWithBlankLine() {
		String ndjson = "{\"model\":\"m\",\"message\":{\"content\":\"Hel\"},\"done\":false}\n"
			+ "\n"
			+ "{\"model\":\"m\",\"message\":{\"content\":\"lo\"},\"done\":false}\n";
		this.server.createContext("/", ex -> {
			ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
			byte[] out = ndjson.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, out.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(out);
			}
		});
		OllamaClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(),
			c -> {
				if (c.deltaText() != null) {
					sb.append(c.deltaText());
				}
			});
		assertEquals("Hello", sb.toString());
	}

	/** 非 2xx 且错误体读取抛 IOException（声明长度与实际不符）→ 回退空体并 mapError。 */
	@Test
	public void testStreamNon2xxErrorBodyReadFails() {
		this.server.createContext("/", ex -> {
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(500, 200L);
			try (OutputStream os = ex.getResponseBody()) {
				os.write("{\"error\":\"partial\"}".getBytes(StandardCharsets.UTF_8));
			}
		});
		OllamaClient client = newClient();
		assertThrows(com.sure.ai.exception.AiApiException.class, () -> client.chatStream(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(), c -> { }));
		client.close();
	}

	/** 200 响应报文长度与声明不符，读取流抛 IOException → AiException(NDJSON read failed)。 */
	@Test
	public void testStreamTruncatedRead() {
		this.server.createContext("/", ex -> {
			ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
			ex.sendResponseHeaders(200, 100L);
			try (OutputStream os = ex.getResponseBody()) {
				os.write("{\"model\":\"m\",\"message\":{\"content\":\"a\"},\"done\":false}\n"
					.getBytes(StandardCharsets.UTF_8));
			}
		});
		OllamaClient client = newClient();
		AiException e = assertThrows(AiException.class, () -> client.chatStream(
			ChatRequest.builder().model("m").messages(ChatMessage.user("hi")).build(), c -> { }));
		assertTrue(e.getMessage(), e.getMessage().contains("NDJSON read failed"));
		client.close();
	}

	/** resolveUrl 反射分支：baseUrl 空抛错、尾斜杠合并、无斜杠补全。 */
	@Test
	public void testResolveUrlBranches() throws Exception {
		Method m = OllamaClient.class.getDeclaredMethod("resolveUrl", String.class);
		m.setAccessible(true);

		OllamaClient noBase = new OllamaClient(AiConfig.builder().apiKey("x").build());
		assertThrows(AiException.class, () -> invokeResolve(m, noBase, "/api/chat"));

		OllamaClient trailing = new OllamaClient(
			AiConfig.builder().apiKey("x").baseUrl("http://h:11434/").build());
		assertEquals("http://h:11434/api/chat", m.invoke(trailing, "/api/chat"));

		OllamaClient plain = new OllamaClient(
			AiConfig.builder().apiKey("x").baseUrl("http://h:11434").build());
		assertEquals("http://h:11434/api/chat", m.invoke(plain, "api/chat"));
	}

	/** 反射调用并解包 InvocationTargetException。 */
	private static void invokeResolve(Method m, Object target, String path) throws Exception {
		try {
			m.invoke(target, path);
		}
		catch (java.lang.reflect.InvocationTargetException ex) {
			if (ex.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw ex;
		}
	}
}
