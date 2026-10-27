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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;

/**
 * {@link DoubaoTtsClient} 测试：本地 HttpServer mock create 接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class DoubaoTtsClientTest {

	private static final String API_KEY = "tts-key";
	private static final byte[] AUDIO = "fake-mp3-audio-bytes".getBytes(StandardCharsets.UTF_8);

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
	private final AtomicReference<String> resourceHeader = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();

	/** 业务错误模式：null=成功，"error"=code!=0。 */
	private String mode;

	/** 启动本地 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		this.mode = null;
		registerHandlers();
	}

	/** 停止服务并清理单例。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		DoubaoUtil.resetTtsClient();
	}

	/** 注册 mock 路由。 */
	private void registerHandlers() {
		this.server.createContext("/", exchange -> {
			this.apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
			this.resourceHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Resource-Id"));
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.lastBody.set(new String(in, StandardCharsets.UTF_8));
			String b64 = Base64.getEncoder().encodeToString(AUDIO);
			String resp;
			if ("error".equals(this.mode)) {
				resp = "{\"code\":1001,\"data\":\"\",\"message\":\"invalid\"}";
			} else {
				resp = "{\"code\":0,\"data\":\"" + b64 + "\",\"message\":\"success\"}";
			}
			byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
	}

	/** 构造指向 mock 的客户端。 */
	private DoubaoTtsClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(API_KEY).baseUrl(this.baseUrl).build();
		return new DoubaoTtsClient(cfg);
	}

	/** 成功：X-Api-Key 头正确、base64 音频解码后字节一致。 */
	@Test
	public void testSynthesizeSuccess() {
		DoubaoTtsClient client = newClient();
		TtsResponse resp = client.synthesize(TtsRequest.builder()
			.model(DoubaoModels.SEED_TTS_2_0).input("你好豆包")
			.voice(DoubaoModels.DOUBAO_TTS_SPEAKER_DEFAULT)
			.responseFormat("mp3").sampleRate(24000).build());
		assertEquals(API_KEY, this.apiKeyHeader.get());
		assertEquals(DoubaoModels.SEED_TTS_2_0, this.resourceHeader.get());
		String body = this.lastBody.get();
		assertTrue(body.contains("\"text\":\"你好豆包\""));
		assertTrue(body.contains(DoubaoModels.DOUBAO_TTS_SPEAKER_DEFAULT));
		assertTrue(body.contains("\"format\":\"mp3\""));
		assertTrue(body.contains("\"sample_rate\":24000"));
		assertTrue(resp.audioLength() > 0);
		assertArrayEquals(AUDIO, resp.audio());
		assertEquals("mp3", resp.format());
		client.close();
	}

	/** 业务 code != 0 抛 AiApiException。 */
	@Test
	public void testBusinessError() {
		this.mode = "error";
		DoubaoTtsClient client = newClient();
		assertThrows(AiApiException.class,
			() -> client.synthesize(TtsRequest.of(DoubaoModels.SEED_TTS_2_0, "x", "v")));
		client.close();
	}

	/** name() 与默认 baseUrl。 */
	@Test
	public void testNameAndDefaults() {
		assertEquals("doubao-tts", newClient().name());
		assertEquals("https://openspeech.bytedance.com", DoubaoTtsClient.DEFAULT_BASE_URL);
	}

	/** Util：resetTtsClient 将单例容器置空（反射注入→reset→断言未初始化）。 */
	@Test
	@SuppressWarnings("unchecked")
	public void testUtilReset() throws Exception {
		java.lang.reflect.Field f = DoubaoUtil.class.getDeclaredField("TTS");
		f.setAccessible(true);
		SingletonHolder<DoubaoTtsClient> holder =
			(SingletonHolder<DoubaoTtsClient>) f.get(null);
		holder.set(newClient());
		assertTrue(holder.isInitialized());
		DoubaoUtil.resetTtsClient();
		assertFalse(holder.isInitialized());
	}
}
