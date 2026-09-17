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

package com.sure.ai.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;

/**
 * {@link AzureSttClient} 测试：本地 HttpServer mock 短音频识别接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class AzureSttClientTest {

	private static final String SPEECH_KEY = "speech-key";

	private HttpServer server;
	private String baseUrl;
	private String subKey;
	private String requestPath;
	private String requestContentType;
	byte[] requestBody;

	/** 失败模式：null=正常，"err"=识别失败，"badStatus"=HTTP 400。 */
	private String mode;

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.mode = null;
		register();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 注册 mock 路由。 */
	private void register() {
		this.server.createContext("/stt/speech/recognition/conversation/cognitiveservices/v1",
			exchange -> {
				this.subKey = exchange.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Key");
				this.requestPath = exchange.getRequestURI().toString();
				this.requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
				this.requestBody = exchange.getRequestBody().readAllBytes();
				if ("badStatus".equals(this.mode)) {
					respond(exchange, 400, "{\"error\":\"bad\"}");
					return;
				}
				if ("err".equals(this.mode)) {
					respond(exchange, 200, "{\"RecognitionStatus\":\"NoMatch\"}");
					return;
				}
				respond(exchange, 200, "{\"RecognitionStatus\":\"Success\","
					+ "\"DisplayText\":\"你好世界\",\"Offset\":100000,\"Duration\":500000}");
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
	private AzureSttClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(SPEECH_KEY).baseUrl(this.baseUrl).build();
		return new AzureSttClient(cfg);
	}

	/** 成功：DisplayText 正确、请求体为二进制音频、Ocp-Apim 头、language 查询参数。 */
	@Test
	public void testTranscribe() {
		AzureSttClient client = newClient();
		byte[] audio = new byte[] { 1, 2, 3, 4 };
		SttResponse resp = client.transcribe(SttRequest.builder()
			.model("azure-stt").audioData(audio).language("zh-CN").build());
		assertEquals("你好世界", resp.text());
		assertEquals(SPEECH_KEY, this.subKey);
		assertTrue(this.requestPath.contains("language=zh-CN"));
		assertTrue(this.requestPath.contains("format=simple"));
		assertEquals(AzureSttClient.DEFAULT_CONTENT_TYPE, this.requestContentType);
		assertEquals(4, this.requestBody.length);
		assertEquals(1, this.requestBody[0]);
		client.close();
	}

	/** 识别失败：RecognitionStatus 非 Success 抛 AiException。 */
	@Test
	public void testNoMatch() {
		this.mode = "err";
		AzureSttClient client = newClient();
		assertThrows(AiException.class,
			() -> client.transcribe(SttRequest.of("azure-stt", new byte[] { 9 })));
		client.close();
	}

	/** HTTP 400 映射为 AiException。 */
	@Test
	public void testBadStatus() {
		this.mode = "badStatus";
		AzureSttClient client = newClient();
		assertThrows(AiException.class,
			() -> client.transcribe(SttRequest.of("azure-stt", new byte[] { 9 })));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("azure-stt", newClient().name());
	}
}
