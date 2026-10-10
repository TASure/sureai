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

package com.sure.ai.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link AbstractAiClient} 受保护工具方法与 URL 拼装/代理分支测试。
 *
 * <p>使用本地 HttpServer（127.0.0.1）触发错误响应路径，其余受保护方法直接断言。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public class AbstractAiClientEdgeTest {

	private HttpServer server;
	private String baseUrl;

	/** 测试用具体客户端：暴露受保护方法。 */
	public static class TestClient extends AbstractAiClient {
		TestClient(AiConfig config) {
			super(config);
		}

		@Override
		protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
			requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
		}

		String namePublic() {
			return name();
		}

		java.util.Set<Capability> capabilitiesPublic() {
			return capabilities();
		}

		void notifyPublic(String model, long p, long c, long t) {
			notifyTokenUsage(model, p, c, t);
		}

		static String encodePublic(String s) {
			return encodePathSegment(s);
		}

		AiException mapErrorPublic(int status, String body) {
			return mapError(status, body);
		}

		String toJsonPublic(Object o) {
			return toJson(o);
		}

		JsonObject doPostRawPublic(String path, JsonObject body) {
			return doPostRaw(path, body).json();
		}

		JsonObject doGetRawPublic(String path) {
			return doGetRaw(path).json();
		}

		byte[] doPostBinaryPublic(String path, JsonObject body) {
			return doPostBinary(path, body);
		}

		AbstractAiClient.PostResult doPostMultipartPublic(String path) {
			return doPostMultipart(path, Map.of("a", "1"), "file", "f.bin",
				"application/octet-stream", new byte[] { 1, 2 });
		}
	}

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

	private TestClient newClient(AiConfig cfg) {
		return new TestClient(cfg);
	}

	/** name() 默认实现与 capabilities() 默认为空集。 */
	@Test
	public void testNameAndCapabilitiesDefaults() {
		TestClient c = newClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build());
		assertEquals("TestClient", c.namePublic());
		assertTrue(c.capabilitiesPublic().isEmpty());
	}

	/** encodePathSegment 空值/空格/波浪号编码。 */
	@Test
	public void testEncodePathSegment() {
		assertEquals(null, TestClient.encodePublic(null));
		assertEquals("", TestClient.encodePublic(""));
		assertEquals("a%20b", TestClient.encodePublic("a b"));
		assertEquals("~", TestClient.encodePublic("~"));
	}

	/** mapError 两参委托。 */
	@Test
	public void testMapErrorDelegate() {
		AiException e = newClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build())
			.mapErrorPublic(500, "body");
		assertTrue(e instanceof com.sure.ai.exception.AiApiException);
	}

	/** toJson 序列化对象。 */
	@Test
	public void testToJson() {
		JsonObject o = new JsonObject();
		o.put("x", 1);
		String s = newClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl).build())
			.toJsonPublic(o);
		assertTrue(s.contains("\"x\":1"));
	}

	/** notifyTokenUsage 挂载指标时回调。 */
	@Test
	public void testNotifyTokenUsage() {
		AtomicReference<long[]> seen = new AtomicReference<>();
		MetricsCollector mc = new MetricsCollector() {
			@Override
			public void onTokenUsage(String model, long promptTokens, long completionTokens,
					long totalTokens) {
				seen.set(new long[] { promptTokens, completionTokens, totalTokens });
			}
		};
		TestClient c = newClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.metricsCollector(mc).build());
		c.notifyPublic("gpt", 5, 3, 8);
		assertEquals(8L, seen.get()[2]);
	}

	/** baseUrl 空白时 resolveUrl 抛异常。 */
	@Test
	public void testBlankBaseUrlThrows() {
		TestClient c = newClient(AiConfig.builder().apiKey("k").build());
		assertThrows(AiException.class, () -> c.doGetRawPublic("/chat"));
	}

	/** 代理配置生效（构造期解析 host:port，不触发网络）。 */
	@Test
	public void testProxyConfigured() {
		TestClient c = newClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.proxy("127.0.0.1:8888").build());
		assertTrue(c != null);
	}

	/** 错误响应路径：doPostBinary 非 2xx 触发 errorBodyReader。 */
	@Test
	public void testBinaryErrorPath() {
		this.server.createContext("/", ex -> {
			byte[] b = "err".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(500, b.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(b);
			}
		});
		TestClient c = newClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.maxRetries(0).build());
		JsonObject body = new JsonObject();
		assertThrows(AiException.class, () -> c.doPostBinaryPublic("/tts", body));
	}

	/** 错误响应路径：doPostMultipart 非 2xx。 */
	@Test
	public void testMultipartErrorPath() {
		this.server.createContext("/", new com.sun.net.httpserver.HttpHandler() {
			@Override
			public void handle(HttpExchange ex) throws IOException {
				byte[] b = "err".getBytes(StandardCharsets.UTF_8);
				ex.sendResponseHeaders(500, b.length);
				try (OutputStream os = ex.getResponseBody()) {
					os.write(b);
				}
			}
		});
		TestClient c = newClient(AiConfig.builder().apiKey("k").baseUrl(this.baseUrl)
			.maxRetries(0).build());
		assertThrows(AiException.class, () -> c.doPostMultipartPublic("/stt"));
	}

	/** URL 拼接：base 带尾斜杠 + path 带前导斜杠。 */
	@Test
	public void testUrlJoinDoubleSlash() {
		AtomicReference<String> seen = new AtomicReference<>();
		this.server.createContext("/", ex -> {
			seen.set(ex.getRequestURI().getPath());
			byte[] b = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, b.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(b);
			}
		});
		TestClient c = newClient(AiConfig.builder().apiKey("k")
			.baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1/")
			.maxRetries(0).build());
		c.doGetRawPublic("/chat");
		assertEquals("/v1/chat", seen.get());
	}

	/** URL 拼接：base 无尾斜杠 + path 无前导斜杠。 */
	@Test
	public void testUrlJoinNoSlash() {
		AtomicReference<String> seen = new AtomicReference<>();
		this.server.createContext("/", ex -> {
			seen.set(ex.getRequestURI().getPath());
			byte[] b = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, b.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(b);
			}
		});
		TestClient c = newClient(AiConfig.builder().apiKey("k")
			.baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1")
			.maxRetries(0).build());
		c.doGetRawPublic("chat");
		assertEquals("/v1/chat", seen.get());
	}

	/** extraHeaders 与 signRequest 签名头（含 Host 跳过）均应用到请求。 */
	@Test
	public void testExtraHeadersAndSignRequest() {
		AtomicReference<String> custom = new AtomicReference<>();
		AtomicReference<String> sign = new AtomicReference<>();
		this.server.createContext("/", ex -> {
			custom.set(ex.getRequestHeaders().getFirst("X-Custom"));
			sign.set(ex.getRequestHeaders().getFirst("X-Sign"));
			byte[] b = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, b.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(b);
			}
		});
		TestClient c = new TestClient(AiConfig.builder().apiKey("k")
			.baseUrl(this.baseUrl).maxRetries(0)
			.extraHeader("X-Custom", "v123").build()) {
			@Override
			protected java.util.Map<String, String> signRequest(String method, String url, String body) {
				java.util.Map<String, String> m = new java.util.HashMap<>();
				m.put("Host", "should-skip");
				m.put("X-Sign", "sig");
				return m;
			}
		};
		c.doGetRawPublic("/chat");
		assertEquals("v123", custom.get());
		assertEquals("sig", sign.get());
	}
}
