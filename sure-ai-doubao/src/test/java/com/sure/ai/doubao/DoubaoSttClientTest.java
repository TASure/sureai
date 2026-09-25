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

package com.sure.ai.doubao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;

/**
 * {@link DoubaoSttClient} 测试：本地 HttpServer mock submit/query 接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class DoubaoSttClientTest {

	private static final String API_KEY = "asr-key";
	private static final String TEXT = "你好，豆包语音识别";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
	private final AtomicReference<String> resourceHeader = new AtomicReference<>();
	private final AtomicReference<String> submitPath = new AtomicReference<>();
	private final AtomicReference<String> queryPath = new AtomicReference<>();
	private final AtomicReference<String> submitBody = new AtomicReference<>();
	private final AtomicInteger queryHits = new AtomicInteger();

	/** 失败模式：null=成功，"pending"=一直无结果。 */
	private String mode;

	/** 启动本地 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.queryHits.set(0);
		this.mode = null;
		registerHandlers();
	}

	/** 停止服务并恢复轮询参数。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		DoubaoSttClient.POLL_INTERVAL_MS = 2000L;
		DoubaoSttClient.MAX_WAIT_MS = 120000L;
		DoubaoUtil.resetSttClient();
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			this.apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
			this.resourceHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Resource-Id"));
			if (path.endsWith("/auc/bigmodel/submit")) {
				this.submitPath.set(path);
				byte[] in = exchange.getRequestBody().readAllBytes();
				this.submitBody.set(new String(in, StandardCharsets.UTF_8));
				respond(exchange, 200, "{\"code\":0,\"id\":\"asr-1\"}");
				return;
			}
			if (path.endsWith("/auc/bigmodel/query")) {
				this.queryPath.set(path);
				int hits = this.queryHits.incrementAndGet();
				if ("pending".equals(this.mode) || hits == 1) {
					respond(exchange, 200, "{\"code\":0}");
					return;
				}
				respond(exchange, 200, "{\"code\":0,\"result\":{\"text\":\"" + TEXT + "\","
					+ "\"utterances\":[{\"text\":\"" + TEXT + "\",\"start_time\":0,\"end_time\":1000}]}}");
				return;
			}
			respond(exchange, 404, "{}");
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

	/** 构造指向 mock 的客户端。 */
	private DoubaoSttClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new DoubaoSttClient(cfg);
	}

	/** 成功：X-Api-Key 头、submit/query 路径、data URI、转写文本正确。 */
	@Test
	public void testSuccess() {
		DoubaoSttClient client = newClient();
		byte[] audio = "fake-audio".getBytes(StandardCharsets.UTF_8);
		SttResponse resp = client.transcribe(SttRequest.builder()
			.model(DoubaoModels.VOLC_BIGASR_AUC).audioData(audio)
			.contentType("audio/mpeg").fileName("audio.mp3").build());
		assertEquals(API_KEY, this.apiKeyHeader.get());
		assertEquals(DoubaoModels.VOLC_BIGASR_AUC, this.resourceHeader.get());
		assertTrue(this.submitPath.get().endsWith("/api/v3/auc/bigmodel/submit"));
		assertTrue(this.queryPath.get().endsWith("/api/v3/auc/bigmodel/query"));
		String body = this.submitBody.get();
		assertTrue(body.contains("data:audio/mpeg;base64,"));
		assertTrue(body.contains("\"format\":\"mp3\""));
		assertTrue(body.contains("\"model_name\":\"bigmodel\""));
		assertEquals(TEXT, resp.text());
		assertEquals(2, this.queryHits.get());
		client.close();
	}

	/** 超时：始终无 result，超过最大等待抛 AiTimeoutException。 */
	@Test
	public void testTimeout() {
		this.mode = "pending";
		DoubaoSttClient.POLL_INTERVAL_MS = 30L;
		DoubaoSttClient.MAX_WAIT_MS = 150L;
		DoubaoSttClient client = newClient();
		byte[] audio = "fake-audio".getBytes(StandardCharsets.UTF_8);
		assertThrows(AiTimeoutException.class,
			() -> client.transcribe(SttRequest.of(DoubaoModels.VOLC_BIGASR_AUC, audio)));
		assertTrue(this.queryHits.get() > 1);
		client.close();
	}

	/** name() 与默认 baseUrl。 */
	@Test
	public void testNameAndDefaults() {
		assertEquals("doubao-stt", newClient().name());
		assertEquals("https://openspeech.bytedance.com", DoubaoSttClient.DEFAULT_BASE_URL);
	}

	/** Util：resetSttClient 将单例字段置 null（反射注入→reset→断言为 null）。 */
	@Test
	public void testUtilReset() throws Exception {
		java.lang.reflect.Field f = DoubaoUtil.class.getDeclaredField("sttClient");
		f.setAccessible(true);
		f.set(null, newClient());
		assertNotNull(f.get(null));
		DoubaoUtil.resetSttClient();
		assertNull(f.get(null));
	}
}
