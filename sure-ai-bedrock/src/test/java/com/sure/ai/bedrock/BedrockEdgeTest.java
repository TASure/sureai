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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * {@link BedrockClient} / {@link BedrockUtil} 边界分支测试。
 *
 * <p>聚焦覆盖：inferenceConfig topP 透传、defaultModelId 兜底、缺 model 抛异常、
 * signer()/host()/endpoint() 访问器、BedrockUtil 懒加载与便捷方法、私有构造器。</p>
 *
 * @author sureai
 */
public class BedrockEdgeTest {

	private HttpServer server;
	private String endpoint;
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

	/** 注册固定 JSON 响应。 */
	private void handle(int status, String body) {
		this.server.createContext("/", exchange -> {
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			respond(exchange, status, body);
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

	/** 成功响应模板。 */
	private static String okResponse() {
		return "{\"output\":{\"message\":{\"content\":[{\"text\":\"ok\"}]}},"
			+ "\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1,"
			+ "\"totalTokens\":2}}";
	}

	/** inferenceConfig topP 透传。 */
	@Test
	public void testTopPTransmitted() {
		handle(200, okResponse());
		BedrockClient client = newClient();
		client.chat(ChatRequest.builder().model(BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET)
			.messages(List.of(ChatMessage.user("hi"))).topP(0.9).build());
		assertTrue(this.lastBody.get().contains("\"topP\":0.9"));
		client.close();
	}

	/** defaultModelId 兜底：直接反射调用 resolveModel 传入空白 model。 */
	@Test
	public void testDefaultModelIdFallback() throws Exception {
		BedrockClient client = newClient();
		java.lang.reflect.Method m = BedrockClient.class.getDeclaredMethod("resolveModel", String.class);
		m.setAccessible(true);
		String result = (String) m.invoke(client, "  ");
		assertEquals(BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET, result);
		client.close();
	}

	/** 缺 model 且无 defaultModelId → AiException。 */
	@Test
	public void testNoModelThrows() throws Exception {
		BedrockClient client = new BedrockClient("AKID", "secret", null, "us-east-1", null,
			this.endpoint);
		java.lang.reflect.Method m = BedrockClient.class.getDeclaredMethod("resolveModel", String.class);
		m.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(client, "  "));
		client.close();
	}

	/** signer() 访问器。 */
	@Test
	public void testSignerAccessor() {
		BedrockClient client = newClient();
		assertNotNull(client.signer());
		assertNotNull(client.host());
		assertNotNull(client.endpoint());
		client.close();
	}

	/** embedNotSupported 抛 AiException。 */
	@Test
	public void testEmbedNotSupported() {
		BedrockClient client = newClient();
		assertThrows(AiException.class, client::embedNotSupported);
		client.close();
	}

	/** 流式分片非对象（data: null）→ handleStreamChunk 静默 return。 */
	@Test
	public void testStreamNonObjectChunk() {
		String sse = "event: messageStart\n"
			+ "data: {\"eventType\":\"messageStart\",\"message\":{\"role\":\"assistant\"}}\n\n"
			+ "data: null\n\n"
			+ "event: messageStop\n"
			+ "data: {\"eventType\":\"messageStop\",\"stopReason\":\"end_turn\"}\n\n";
		this.server.createContext("/", exchange -> {
			byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		BedrockClient client = newClient();
		StringBuilder sb = new StringBuilder();
		client.chatStream(ChatRequest.builder().model(BedrockModels.ANTHROPIC_CLAUDE_3_5_SONNET)
			.messages(List.of(ChatMessage.user("hi"))).build(),
			c -> {
				if (c.deltaText() != null) {
					sb.append(c.deltaText());
				}
			});
		client.close();
	}

	/** BedrockUtil.create()：空环境映射抛 AiException。 */
	@Test
	public void testCreateEmptyEnv() {
		assertThrows(AiException.class, () -> BedrockUtil.create(Map.of()));
	}

	/** BedrockUtil.create() 无参版本：从 System.getenv() 读取（无凭证时抛 AiException）。 */
	@Test
	public void testCreateNoArg() {
		assertThrows(AiException.class, BedrockUtil::create);
	}

	/** BedrockUtil 私有构造器。 */
	@Test
	public void testPrivateCtor() throws Exception {
		Constructor<BedrockUtil> c = BedrockUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** BedrockUtil 便捷方法：注入 mock 客户端后调用 chat/chatStream。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilConvenience() throws Exception {
		Field f = BedrockUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		SingletonHolder<BedrockClient> holder = (SingletonHolder<BedrockClient>) f.get(null);
		this.server.createContext("/", exchange -> {
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			String path = exchange.getRequestURI().getPath();
			if (path.endsWith("/converse-stream")) {
				String sse = "event: messageStart\n"
					+ "data: {\"eventType\":\"messageStart\",\"message\":{\"role\":\"assistant\"}}\n\n"
					+ "event: contentBlockDelta\n"
					+ "data: {\"eventType\":\"contentBlockDelta\",\"delta\":{\"text\":\"ok\"}}\n\n"
					+ "event: messageStop\n"
					+ "data: {\"eventType\":\"messageStop\",\"stopReason\":\"end_turn\"}\n\n";
				byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(bytes);
				}
				return;
			}
			respond(exchange, 200, okResponse());
		});
		holder.set(newClient());
		try {
			ChatResponse r = BedrockUtil.chat("claude", "hi");
			assertEquals("ok", r.firstText());
			ChatResponse r2 = BedrockUtil.chat(ChatRequest.builder().model("claude")
				.messages(List.of(ChatMessage.user("hi"))).build());
			assertEquals("ok", r2.firstText());
			StringBuilder sb = new StringBuilder();
			BedrockUtil.chatStream("claude", "hi", c -> {
				if (c.deltaText() != null) {
					sb.append(c.deltaText());
				}
			});
			assertEquals("ok", sb.toString());
			BedrockUtil.chatStream(ChatRequest.builder().model("claude")
				.messages(List.of(ChatMessage.user("hi"))).build(),
				c -> { });
		} finally {
			holder.reset();
		}
	}

	/** BedrockUtil 懒加载：重置 HOLDER 后从环境变量加载（空环境抛异常）。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilLazyLoad() throws Exception {
		Field f = BedrockUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		SingletonHolder<BedrockClient> holder = (SingletonHolder<BedrockClient>) f.get(null);
		holder.reset();
		try {
			assertThrows(AiException.class, BedrockUtil::client);
		} finally {
			holder.set(newClient());
		}
	}

	/** 直接触发 HOLDER 的 defaultSupplier lambda，覆盖 L84。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testHolderSupplierInvoked() throws Exception {
		Field f = BedrockUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		SingletonHolder<BedrockClient> holder = (SingletonHolder<BedrockClient>) f.get(null);
		holder.reset();
		Field sf = SingletonHolder.class.getDeclaredField("defaultSupplier");
		sf.setAccessible(true);
		java.util.function.Supplier<BedrockClient> supplier =
			(java.util.function.Supplier<BedrockClient>) sf.get(holder);
		assertThrows(AiException.class, supplier::get);
		holder.set(newClient());
	}

	/** BedrockUtil.create() 无参版本：env 凭证齐全时成功创建（覆盖 L99）。 */
	@Test
	public void testCreateNoArgWithEnv() {
		EnvVars env = EnvVars.begin();
		try {
			env.set("AWS_ACCESS_KEY_ID", "test-ak");
			env.set("AWS_SECRET_ACCESS_KEY", "test-sk");
			env.set("AWS_REGION", "us-east-1");
			BedrockClient c = BedrockUtil.create();
			assertNotNull(c);
			c.close();
		} finally {
			env.restore();
		}
	}

	/** HOLDER lambda：env 凭证齐全时成功调用（覆盖 L84）。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testHolderSupplierWithEnv() throws Exception {
		Field f = BedrockUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		SingletonHolder<BedrockClient> holder = (SingletonHolder<BedrockClient>) f.get(null);
		holder.reset();
		EnvVars env = EnvVars.begin();
		try {
			env.set("AWS_ACCESS_KEY_ID", "test-ak");
			env.set("AWS_SECRET_ACCESS_KEY", "test-sk");
			env.set("AWS_REGION", "us-east-1");
			BedrockClient c = BedrockUtil.client();
			assertNotNull(c);
			c.close();
		} finally {
			env.restore();
			holder.set(newClient());
		}
	}
}
