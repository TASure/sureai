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

package com.sure.ai.baidu;

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
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;

/**
 * {@link BaiduImageClient} 测试：本地 HttpServer mock OAuth、提交与轮询接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class BaiduImageClientTest {

	private static final String API_KEY = "test-ak";
	private static final String SECRET_KEY = "test-sk";
	private static final String TOKEN = "test-token";
	private static final String IMAGE_URL = "https://example.com/ernie.png";

	private HttpServer server;
	private String baseUrl;
	private final AtomicInteger tokenHits = new AtomicInteger();
	private final AtomicInteger pollHits = new AtomicInteger();
	private final AtomicReference<String> tokenQuery = new AtomicReference<>();
	private final AtomicReference<String> submitPath = new AtomicReference<>();
	private final AtomicReference<String> submitBody = new AtomicReference<>();
	private final AtomicReference<String> oauthMethod = new AtomicReference<>();
	private final AtomicReference<String> oauthContentType = new AtomicReference<>();
	private final AtomicReference<String> oauthBody = new AtomicReference<>();
	private final AtomicReference<String> submitAuthz = new AtomicReference<>();

	/** 失败模式：null=正常，"submitFail"=提交失败，"failed"=任务失败，"pending"=一直运行。 */
	private String mode;

	/** 启动本地 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.tokenHits.set(0);
		this.pollHits.set(0);
		this.oauthMethod.set(null);
		this.oauthContentType.set(null);
		this.oauthBody.set(null);
		this.submitAuthz.set(null);
		this.mode = null;
		registerHandlers();
	}

	/** 停止服务并恢复轮询参数。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		BaiduImageClient.POLL_INTERVAL_MS = 2000L;
		BaiduImageClient.MAX_WAIT_MS = 120000L;
		BaiduUtil.resetImageClient();
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String query = exchange.getRequestURI().getQuery();
			if (path.equals("/oauth/2.0/token")) {
				this.tokenHits.incrementAndGet();
				this.tokenQuery.set(query);
				this.oauthMethod.set(exchange.getRequestMethod());
				this.oauthContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				byte[] tin = exchange.getRequestBody().readAllBytes();
				this.oauthBody.set(new String(tin, StandardCharsets.UTF_8));
				respond(exchange, 200,
					"{\"access_token\":\"" + TOKEN + "\",\"expires_in\":2592000}",
					"application/json");
				return;
			}
			if (path.equals("/rpc/2.0/ernievilg/v1/txt2imgv2")) {
				this.submitPath.set(path + (query == null ? "" : "?" + query));
				this.submitAuthz.set(exchange.getRequestHeaders().getFirst("Authorization"));
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.submitBody.set(new String(in, StandardCharsets.UTF_8));
				if ("submitFail".equals(this.mode)) {
					respond(exchange, 200, "{\"code\":110,\"msg\":\"invalid param\",\"data\":{}}",
						"application/json");
					return;
				}
				respond(exchange, 200,
					"{\"code\":0,\"msg\":\"success\",\"data\":{\"task_id\":\"task-1\"}}",
					"application/json");
				return;
			}
			if (path.equals("/rpc/2.0/ernievilg/v1/getImgv2")) {
				int hits = this.pollHits.incrementAndGet();
				if ("failed".equals(this.mode)) {
					respond(exchange, 200,
						"{\"code\":0,\"msg\":\"success\",\"data\":{\"status\":3}}",
						"application/json");
					return;
				}
				if ("pending".equals(this.mode) || hits == 1) {
					respond(exchange, 200,
						"{\"code\":0,\"msg\":\"success\",\"data\":{\"status\":1}}",
						"application/json");
					return;
				}
				respond(exchange, 200, "{\"code\":0,\"msg\":\"success\",\"data\":{\"status\":2,"
					+ "\"img_url\":\"" + IMAGE_URL + "\"}}", "application/json");
				return;
			}
			respond(exchange, 404, "{}", "application/json");
		});
	}

	/** 发送响应。 */
	private static void respond(HttpExchange ex, int status, String body, String contentType)
			throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 构造指向 mock 的客户端。 */
	private BaiduImageClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY).build();
		return new BaiduImageClient(cfg);
	}

	/** 成功：OAuth 凭证走 POST body，提交/轮询走 Bearer 头，轮询两次返回图片 URL。 */
	@Test
	public void testImageGenerationSuccess() {
		BaiduImageClient client = newClient();
		ImageResponse resp = client.generate(ImageRequest.builder()
			.model(BaiduModels.ERNIE_VILG_V2).prompt("红玫瑰").size("1024*1024").build());
		assertEquals(IMAGE_URL, resp.firstUrl());
		assertEquals(1, resp.data().size());
		// OAuth：POST + form-urlencoded，凭证在 body，不在 URL 查询串
		assertEquals("POST", this.oauthMethod.get());
		assertEquals("application/x-www-form-urlencoded", this.oauthContentType.get());
		String ob = this.oauthBody.get();
		assertTrue("oauth body must contain client_id: " + ob, ob.contains("client_id=" + API_KEY));
		assertTrue("oauth body must contain client_secret: " + ob,
			ob.contains("client_secret=" + SECRET_KEY));
		assertTrue(ob.contains("grant_type=client_credentials"));
		String q = this.tokenQuery.get();
		assertTrue("oauth query must be empty: " + q, q == null || q.isBlank());
		assertFalse("oauth query must not contain client_secret: " + q,
			q != null && q.contains("client_secret="));
		// 业务提交：Bearer 头，URL 不带 access_token
		assertEquals("Bearer " + TOKEN, this.submitAuthz.get());
		assertFalse("submit URL must not contain access_token: " + this.submitPath.get(),
			this.submitPath.get().contains("access_token"));
		String body = this.submitBody.get();
		assertTrue(body.contains("\"prompt\":\"红玫瑰\""));
		assertTrue(body.contains("\"width\":1024"));
		assertTrue(body.contains("\"height\":1024"));
		assertEquals(2, this.pollHits.get());
		assertEquals(1, this.tokenHits.get());
		client.close();
	}

	/** 任务失败：status=3 抛 AiApiException。 */
	@Test
	public void testImageGenerationFailed() {
		this.mode = "failed";
		BaiduImageClient client = newClient();
		assertThrows(AiApiException.class,
			() -> client.generate(BaiduModels.ERNIE_VILG_V2, "bad"));
		client.close();
	}

	/** 提交失败：code!=0 抛 AiApiException，msg 透传。 */
	@Test
	public void testSubmitFailed() {
		this.mode = "submitFail";
		BaiduImageClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class,
			() -> client.generate(BaiduModels.ERNIE_VILG_V2, "x"));
		assertTrue(e.getMessage().contains("invalid param"));
		assertEquals("110", e.getErrorCode());
		client.close();
	}

	/** 超时：轮询始终运行中，超过最大等待抛 AiTimeoutException。 */
	@Test
	public void testImageGenerationTimeout() {
		this.mode = "pending";
		BaiduImageClient.POLL_INTERVAL_MS = 30L;
		BaiduImageClient.MAX_WAIT_MS = 150L;
		BaiduImageClient client = newClient();
		assertThrows(AiTimeoutException.class,
			() -> client.generate(BaiduModels.ERNIE_VILG_V2, "slow"));
		assertTrue(this.pollHits.get() > 1);
		client.close();
	}

	/** token 缓存：两次生成只换一次 token。 */
	@Test
	public void testTokenCached() {
		BaiduImageClient client = newClient();
		client.generate(BaiduModels.ERNIE_VILG_V2, "a");
		client.generate(BaiduModels.ERNIE_VILG_V2, "b");
		assertEquals(1, this.tokenHits.get());
		client.close();
	}

	/** name() 与默认 baseUrl。 */
	@Test
	public void testNameAndDefaults() {
		assertEquals("baidu-image", newClient().name());
		assertEquals("https://aip.baidubce.com", BaiduImageClient.DEFAULT_BASE_URL);
	}

	/** Models 常量。 */
	@Test
	public void testModelsConstants() {
		assertEquals("ernie-vilg-v2", BaiduModels.ERNIE_VILG_V2);
		List<String> ids = List.of(BaiduModels.ERNIE_VILG_V2);
		for (String id : ids) {
			assertNotNull(id);
		}
	}

	/** Util 重置方法：注入 imageClient 后 reset，反射断言字段置 null。 */
	@Test
	public void testUtilReset() throws Exception {
		java.lang.reflect.Field f = BaiduUtil.class.getDeclaredField("imageClient");
		f.setAccessible(true);
		f.set(null, newClient());
		assertNotNull(f.get(null));
		BaiduUtil.resetImageClient();
		assertNull(f.get(null));
	}
}
