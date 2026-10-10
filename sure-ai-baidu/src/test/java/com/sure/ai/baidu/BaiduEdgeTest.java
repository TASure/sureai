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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
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
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.TtsRequest;

/**
 * {@link BaiduClient} / {@link BaiduImageClient} / {@link BaiduUtil} 边界分支测试。
 *
 * <p>聚焦覆盖：chatStream/embed 的 AiApiException→enrichError 路径、postForm/postJson
 * 非 2xx 与中断路径、enrichError 各分支（空 body / AiAuthException / 非 JSON）、
 * BaiduImageClient fetchToken 错误路径、BaiduUtil image 便捷方法、私有构造器。</p>
 *
 * @author sureai
 */
public class BaiduEdgeTest {

	private static final String API_KEY = "edge-ak";
	private static final String SECRET_KEY = "edge-sk";
	private static final String TOKEN = "edge-token";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> mode = new AtomicReference<>("ok");
	private final AtomicReference<String> lastPath = new AtomicReference<>();

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port;
		this.mode.set("ok");
		this.lastPath.set(null);
		registerHandlers();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		BaiduUtil.resetImageClient();
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			this.lastPath.set(path);
			if (path.equals("/oauth/2.0/token")) {
				String m = this.mode.get();
				if ("token500".equals(m)) {
					respond(exchange, 500, "{\"error\":\"boom\"}", "application/json");
					return;
				}
				if ("tokenNoAccess".equals(m)) {
					respond(exchange, 200, "{\"expires_in\":2592000}", "application/json");
					return;
				}
				respond(exchange, 200,
					"{\"access_token\":\"" + TOKEN + "\",\"expires_in\":2592000}",
					"application/json");
				return;
			}
			if (path.contains("/chat/")) {
				byte[] in = exchange.getRequestBody().readAllBytes();
				String body = new String(in, StandardCharsets.UTF_8);
				if (body.contains("\"stream\":true")) {
					// 流式：若请求带 __err__ 返回 400
					if (body.contains("__err__")) {
						respond(exchange, 400,
							"{\"error_code\":110,\"error_msg\":\"invalid\"}", "application/json");
						return;
					}
					String sse = "data: {\"id\":\"r\",\"result\":\"hi\",\"is_end\":true}\n\n";
					respond(exchange, 200, sse, "text/event-stream");
					return;
				}
				if (body.contains("__err__")) {
					respond(exchange, 400,
						"{\"error_code\":110,\"error_msg\":\"invalid\"}", "application/json");
					return;
				}
				respond(exchange, 200, "{\"id\":\"r\",\"result\":\"ok\",\"usage\":{\"prompt_tokens\":1,"
					+ "\"completion_tokens\":1,\"total_tokens\":2}}", "application/json");
				return;
			}
			if (path.contains("/embeddings/")) {
				if ("embErr".equals(this.mode.get())) {
					respond(exchange, 400, "{\"error_code\":2001,\"error_msg\":\"embed bad\"}",
						"application/json");
					return;
				}
				respond(exchange, 200, "{\"id\":\"e\",\"data\":[{\"embedding\":[0.1]}],"
					+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"total_tokens\":1}}",
					"application/json");
				return;
			}
			if (path.equals("/text2audio")) {
				if ("tts500".equals(this.mode.get())) {
					respond(exchange, 500, "{\"err_no\":3001,\"err_msg\":\"server err\"}",
						"application/json");
					return;
				}
				byte[] audio = "fake-mp3".getBytes(StandardCharsets.UTF_8);
				respondBytes(exchange, 200, audio, "audio/mpeg");
				return;
			}
			if (path.equals("/server_api")) {
				if ("stt500".equals(this.mode.get())) {
					respond(exchange, 500, "{\"err_no\":3301,\"err_msg\":\"stt boom\"}",
						"application/json");
					return;
				}
				respond(exchange, 200, "{\"err_no\":0,\"result\":[\"你好\"]}", "application/json");
				return;
			}
			if (path.contains("/ernievilg/")) {
				if (exchange.getRequestMethod().equals("POST") && path.contains("txt2img")) {
					respond(exchange, 200, "{\"code\":0,\"msg\":\"success\","
						+ "\"data\":{\"task_id\":\"task-1\"}}", "application/json");
					return;
				}
				if (exchange.getRequestMethod().equals("POST") && path.contains("getImg")) {
					respond(exchange, 200, "{\"code\":0,\"msg\":\"success\",\"data\":{\"status\":2,"
						+ "\"img_url\":\"https://example.com/rose.png\"}}", "application/json");
					return;
				}
			}
			respond(exchange, 404, "{}", "application/json");
		});
	}

	/** 发送 JSON 响应。 */
	private static void respond(HttpExchange ex, int status, String body, String contentType)
			throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 发送二进制响应。 */
	private static void respondBytes(HttpExchange ex, int status, byte[] body, String contentType)
			throws IOException {
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, body.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(body);
		}
	}

	/** 构造指向 mock 的客户端。 */
	private BaiduClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY)
			.extraHeader(BaiduClient.TTS_URL_HEADER, this.baseUrl + "/text2audio")
			.extraHeader(BaiduClient.STT_URL_HEADER, this.baseUrl + "/server_api")
			.build();
		return new BaiduClient(cfg);
	}

	/** chatStream 错误路径：400 → AiApiException 经 enrichError 透传。 */
	@Test
	public void testChatStreamErrorEnriched() {
		BaiduClient client = newClient();
		ChatRequest req = ChatRequest.builder().model("ernie").messages(ChatMessage.user("__err__")).build();
		AiApiException e = assertThrows(AiApiException.class, () -> client.chatStream(req, c -> { }));
		assertEquals("110", e.getErrorCode());
		client.close();
	}

	/** embed 错误路径：400 → AiApiException 经 enrichError 透传。 */
	@Test
	public void testEmbedErrorEnriched() {
		this.mode.set("embErr");
		BaiduClient client = newClient();
		AiApiException e = assertThrows(AiApiException.class,
			() -> client.embed(new EmbeddingRequest("embedding-v1", List.of("hi"))));
		assertEquals("2001", e.getErrorCode());
		client.close();
	}

	/** TTS 非 2xx：postForm 返回 500 → mapError 抛出。 */
	@Test
	public void testTtsNon2xx() {
		this.mode.set("tts500");
		BaiduClient client = newClient();
		assertThrows(AiApiException.class, () -> client.synthesize(
			TtsRequest.of("baidu", "x", BaiduModels.TTS_PER_XIAOMEI)));
		client.close();
	}

	/** STT 非 2xx：postJson 返回 500 → mapError 抛出。 */
	@Test
	public void testSttNon2xx() {
		this.mode.set("stt500");
		BaiduClient client = newClient();
		assertThrows(AiApiException.class, () -> client.transcribe(
			SttRequest.of("baidu", new byte[] { 1 })));
		client.close();
	}

	/** TTS 网络不可达 → postForm IOException 映射 AiTimeoutException。 */
	@Test
	public void testTtsNetworkError() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY)
			.extraHeader(BaiduClient.TTS_URL_HEADER, "http://127.0.0.1:1")
			.extraHeader(BaiduClient.STT_URL_HEADER, this.baseUrl + "/server_api")
			.build();
		BaiduClient client = new BaiduClient(cfg);
		assertThrows(com.sure.ai.exception.AiTimeoutException.class,
			() -> client.synthesize(TtsRequest.of("baidu", "x", BaiduModels.TTS_PER_XIAOMEI)));
		client.close();
	}

	/** STT 网络不可达 → postJson IOException 映射 AiTimeoutException。 */
	@Test
	public void testSttNetworkError() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY)
			.extraHeader(BaiduClient.TTS_URL_HEADER, this.baseUrl + "/text2audio")
			.extraHeader(BaiduClient.STT_URL_HEADER, "http://127.0.0.1:1")
			.build();
		BaiduClient client = new BaiduClient(cfg);
		assertThrows(com.sure.ai.exception.AiTimeoutException.class,
			() -> client.transcribe(SttRequest.of("baidu", new byte[] { 1 })));
		client.close();
	}

	/** TTS 线程中断 → postForm InterruptedException 映射 AiException。 */
	@Test
	public void testTtsInterrupted() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY)
			.extraHeader(BaiduClient.TTS_URL_HEADER, "http://127.0.0.1:1")
			.extraHeader(BaiduClient.STT_URL_HEADER, this.baseUrl + "/server_api")
			.build();
		BaiduClient client = new BaiduClient(cfg);
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, () -> client.synthesize(
				TtsRequest.of("baidu", "x", BaiduModels.TTS_PER_XIAOMEI)));
		} finally {
			Thread.interrupted();
		}
		client.close();
	}

	/** STT 线程中断 → postJson InterruptedException 映射 AiException。 */
	@Test
	public void testSttInterrupted() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY)
			.extraHeader(BaiduClient.TTS_URL_HEADER, this.baseUrl + "/text2audio")
			.extraHeader(BaiduClient.STT_URL_HEADER, "http://127.0.0.1:1")
			.build();
		BaiduClient client = new BaiduClient(cfg);
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, () -> client.transcribe(
				SttRequest.of("baidu", new byte[] { 1 })));
		} finally {
			Thread.interrupted();
		}
		client.close();
	}

	/** enrichError 空 body 分支：异常 rawBody 为空时原样返回。 */
	@Test
	public void testEnrichErrorEmptyBody() throws Exception {
		BaiduClient client = newClient();
		java.lang.reflect.Method m = BaiduClient.class
			.getDeclaredMethod("enrichError", AiApiException.class);
		m.setAccessible(true);
		AiApiException ex = new AiApiException(400, null, "just a message", null);
		Object result = m.invoke(client, ex);
		assertTrue(result instanceof AiApiException);
		client.close();
	}

	/** enrichError AiAuthException 分支：error_code 存在时保留 AiAuthException 类型。 */
	@Test
	public void testEnrichErrorAuthException() throws Exception {
		BaiduClient client = newClient();
		java.lang.reflect.Method m = BaiduClient.class
			.getDeclaredMethod("enrichError", AiApiException.class);
		m.setAccessible(true);
		AiAuthException ex = new AiAuthException(401, "invalid key",
			"{\"error_code\":110,\"error_msg\":\"bad key\"}");
		Object result = m.invoke(client, ex);
		assertTrue(result instanceof AiAuthException);
		client.close();
	}

	/** enrichError 非 JSON body 分支：catch RuntimeException 原样返回。 */
	@Test
	public void testEnrichErrorNonJson() throws Exception {
		BaiduClient client = newClient();
		java.lang.reflect.Method m = BaiduClient.class
			.getDeclaredMethod("enrichError", AiApiException.class);
		m.setAccessible(true);
		AiApiException ex = new AiApiException(500, null, "server err", "not-json{{{");
		Object result = m.invoke(client, ex);
		assertTrue(result instanceof AiApiException);
		client.close();
	}

	/** BaiduImageClient token 端点 500 → AiAuthException。 */
	@Test
	public void testImageToken500() {
		this.mode.set("token500");
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY).build();
		BaiduImageClient client = new BaiduImageClient(cfg);
		assertThrows(AiAuthException.class, () -> client.generate("ernie-vilg-v2", "rose"));
		client.close();
	}

	/** BaiduImageClient token 缺 access_token → AiAuthException。 */
	@Test
	public void testImageTokenNoAccess() {
		this.mode.set("tokenNoAccess");
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY).build();
		BaiduImageClient client = new BaiduImageClient(cfg);
		assertThrows(AiAuthException.class, () -> client.generate("ernie-vilg-v2", "rose"));
		client.close();
	}

	/** BaiduImageClient token 网络不可达 → IOException 映射 AiTimeoutException。 */
	@Test
	public void testImageTokenNetworkError() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl("http://127.0.0.1:1")
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY).build();
		BaiduImageClient client = new BaiduImageClient(cfg);
		assertThrows(com.sure.ai.exception.AiTimeoutException.class,
			() -> client.generate("ernie-vilg-v2", "rose"));
		client.close();
	}

	/** BaiduImageClient token 线程中断 → InterruptedException 映射 AiException。 */
	@Test
	public void testImageTokenInterrupted() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY).build();
		BaiduImageClient client = new BaiduImageClient(cfg);
		Thread.currentThread().interrupt();
		try {
			assertThrows(AiException.class, () -> client.generate("ernie-vilg-v2", "rose"));
		} finally {
			Thread.interrupted();
		}
		client.close();
	}

	/** BaiduUtil image(model, prompt) 便捷方法：注入 mock 图像客户端后调用。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilImageConvenience() throws Exception {
		Field f = BaiduUtil.class.getDeclaredField("IMAGE");
		f.setAccessible(true);
		SingletonHolder<BaiduImageClient> holder =
			(SingletonHolder<BaiduImageClient>) f.get(null);
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY).build();
		holder.set(new BaiduImageClient(cfg));
		ImageResponse resp = BaiduUtil.image("ernie-vilg-v2", "red rose");
		assertNotNull(resp);
	}

	/** BaiduUtil image(request) 便捷方法。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilImageRequest() throws Exception {
		Field f = BaiduUtil.class.getDeclaredField("IMAGE");
		f.setAccessible(true);
		SingletonHolder<BaiduImageClient> holder =
			(SingletonHolder<BaiduImageClient>) f.get(null);
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, SECRET_KEY).build();
		holder.set(new BaiduImageClient(cfg));
		ImageRequest req = ImageRequest.builder().model("ernie-vilg-v2").prompt("cat").build();
		ImageResponse resp = BaiduUtil.image(req);
		assertNotNull(resp);
	}

	/** BaiduModels 私有构造器不可实例化。 */
	@Test
	public void testModelsPrivateCtor() throws Exception {
		Constructor<BaiduModels> c = BaiduModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** BaiduUtil 私有构造器不可实例化。 */
	@Test
	public void testUtilPrivateCtor() throws Exception {
		Constructor<BaiduUtil> c = BaiduUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
