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

package com.sure.ai.otel.langfuse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

/**
 * {@link LangfuseIngestionClient} 与 {@link LangfuseConfig} 单测：用 JDK 内置 {@link HttpServer}
 * 本地 mock Ingestion 端点，断言方法/路径/认证头/请求体结构，零真实网络。
 *
 * @author sureai
 * @since 2.4.0
 */
public class LangfuseIngestionClientTest {

	private static final String PUB = "pk-lf-test";
	private static final String SEC = "sk-lf-test";

	/** 本地 mock 服务端，捕获最近一次请求。 */
	private static final class MockServer {
		final HttpServer server;
		final AtomicReference<String> method = new AtomicReference<>();
		final AtomicReference<String> path = new AtomicReference<>();
		final AtomicReference<String> auth = new AtomicReference<>();
		final AtomicReference<String> contentType = new AtomicReference<>();
		final AtomicReference<String> body = new AtomicReference<>();
		int responseStatus = 200;

		MockServer() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress(0), 0);
			this.server.createContext("/api/public/ingestion", exchange -> {
				this.method.set(exchange.getRequestMethod());
				this.path.set(exchange.getRequestURI().getPath());
				this.auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				this.contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				byte[] buf = exchange.getRequestBody().readAllBytes();
				this.body.set(new String(buf, StandardCharsets.UTF_8));
				exchange.sendResponseHeaders(this.responseStatus, -1);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(new byte[0]);
				}
				exchange.close();
			});
			this.server.start();
		}

		String baseUrl() {
			return "http://localhost:" + this.server.getAddress().getPort();
		}

		void stop() {
			this.server.stop(0);
		}
	}

	/** 成功 POST：方法/路径/认证头/Content-Type/batch 信封正确。 */
	@Test
	public void testPostBatchToIngestionEndpoint() throws Exception {
		MockServer mock = new MockServer();
		try {
			LangfuseConfig cfg = LangfuseConfig.of(mock.baseUrl(), PUB, SEC, "production");
			LangfuseIngestionClient client = new LangfuseIngestionClient(cfg);

			String e1 = "{\"id\":\"evt-1\",\"type\":\"trace-create\",\"timestamp\":\"2026-01-01T00:00:00Z\","
				+ "\"body\":{\"id\":\"trace-1\"}}";
			String e2 = "{\"id\":\"evt-2\",\"type\":\"generation-create\",\"timestamp\":\"2026-01-01T00:00:00Z\","
				+ "\"body\":{\"id\":\"gen-1\",\"traceId\":\"trace-1\"}}";
			client.send(List.of(e1, e2)).join();

			assertEquals("POST", mock.method.get());
			assertEquals("/api/public/ingestion", mock.path.get());
			assertNotNull(mock.auth.get());
			assertTrue("auth should be Basic", mock.auth.get().startsWith("Basic "));
			// 解出 base64 后应为 pk:sk
			String decoded = new String(Base64.getDecoder()
				.decode(mock.auth.get().substring("Basic ".length())), StandardCharsets.UTF_8);
			assertEquals(PUB + ":" + SEC, decoded);
			assertTrue("content-type json", mock.contentType.get().contains("application/json"));
			assertTrue("body wraps batch", mock.body.get().startsWith("{\"batch\":["));
			assertTrue("batch contains evt-1", mock.body.get().contains("\"type\":\"trace-create\""));
			assertTrue("batch contains evt-2", mock.body.get().contains("\"type\":\"generation-create\""));
			assertTrue("two events comma-joined", mock.body.get().contains("},{\"id\":\"evt-2\""));
		} finally {
			mock.stop();
		}
	}

	/** 207 Multi-Status 不视为错误（不抛异常）。 */
	@Test
	public void testMultiStatusAcceptedNoThrow() throws Exception {
		MockServer mock = new MockServer();
		try {
			mock.responseStatus = 207;
			LangfuseIngestionClient client = new LangfuseIngestionClient(
				LangfuseConfig.of(mock.baseUrl(), PUB, SEC, null));
			// 不抛异常即通过
			client.send(List.of("{\"id\":\"e\",\"type\":\"trace-create\",\"timestamp\":\"t\",\"body\":{}}"))
				.join();
			assertEquals("/api/public/ingestion", mock.path.get());
		} finally {
			mock.stop();
		}
	}

	/** 非 2xx：future 正常完成（异常被吞咽），不向调用方抛出。 */
	@Test
	public void testNon2xxSwallowed() throws Exception {
		MockServer mock = new MockServer();
		try {
			mock.responseStatus = 500;
			LangfuseIngestionClient client = new LangfuseIngestionClient(
				LangfuseConfig.of(mock.baseUrl(), PUB, SEC, null));
			client.send(List.of("{\"id\":\"e\",\"type\":\"trace-create\",\"timestamp\":\"t\",\"body\":{}}"))
				.join();
			// join 不抛即通过
		} finally {
			mock.stop();
		}
	}

	/** 禁用配置（缺密钥）：不发请求、服务端收不到任何东西。 */
	@Test
	public void testDisabledConfigNoRequest() throws Exception {
		MockServer mock = new MockServer();
		try {
			LangfuseIngestionClient client = new LangfuseIngestionClient(LangfuseConfig.disabled());
			CompletableFuture<Void> f = client.send(List.of("{\"id\":\"e\"}"));
			f.join();
			assertEquals("no request should hit server", null, mock.method.get());
		} finally {
			mock.stop();
		}
	}

	/** 空事件列表：不发请求。 */
	@Test
	public void testEmptyEventsNoRequest() throws Exception {
		MockServer mock = new MockServer();
		try {
			LangfuseIngestionClient client = new LangfuseIngestionClient(
				LangfuseConfig.of(mock.baseUrl(), PUB, SEC, null));
			client.send(List.of()).join();
			assertEquals(null, mock.method.get());
		} finally {
			mock.stop();
		}
	}

	/** 不可达地址：发送异常被吞咽，future 正常完成，不破坏调用方。 */
	@Test
	public void testUnreachableSwallowed() {
		// 端口 1 几乎必然不可用
		LangfuseConfig cfg = LangfuseConfig.of("http://localhost:1", PUB, SEC, null);
		LangfuseIngestionClient client = new LangfuseIngestionClient(cfg);
		client.send(List.of("{\"id\":\"e\",\"type\":\"trace-create\",\"timestamp\":\"t\",\"body\":{}}"))
			.join();
		// join 不抛即通过
	}

	// ---------- Config ----------

	/** endpoint 结尾斜杠被规整；ingestionUrl 拼接正确。 */
	@Test
	public void testEndpointNormalization() {
		LangfuseConfig c = LangfuseConfig.of("https://example.com/", PUB, SEC, null);
		assertEquals("https://example.com", c.endpoint());
		assertEquals("https://example.com/api/public/ingestion", c.ingestionUrl());
		assertTrue(c.isEnabled());
	}

	/** 缺密钥 → 禁用态。 */
	@Test
	public void testDisabledWhenKeysMissing() {
		assertFalse(LangfuseConfig.of(null, "", SEC, null).isEnabled());
		assertFalse(LangfuseConfig.of(null, PUB, "  ", null).isEnabled());
		assertFalse(LangfuseConfig.disabled().isEnabled());
	}

	/** 默认 host。 */
	@Test
	public void testDefaultHost() {
		LangfuseConfig c = LangfuseConfig.of(null, PUB, SEC, null);
		assertEquals(LangfuseConfig.DEFAULT_HOST, c.endpoint());
	}
}
