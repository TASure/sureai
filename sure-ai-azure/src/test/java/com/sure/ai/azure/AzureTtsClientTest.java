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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;

/**
 * {@link AzureTtsClient} 测试：本地 HttpServer mock SSML 接口。
 *
 * @author sureai
 * @since 0.2.0
 */
public class AzureTtsClientTest {

	private static final String SPEECH_KEY = "speech-key";

	private HttpServer server;
	private String baseUrl;
	private final AtomicReference<String> subKey = new AtomicReference<>();
	private final AtomicReference<String> contentType = new AtomicReference<>();
	private final AtomicReference<String> outFormat = new AtomicReference<>();
	private final AtomicReference<String> body = new AtomicReference<>();

	/** 失败模式：null=正常，"err"=返回 400。 */
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
		this.server.createContext("/cognitiveservices/v1", exchange -> {
			this.subKey.set(exchange.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Key"));
			this.contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			this.outFormat.set(exchange.getRequestHeaders().getFirst("X-Microsoft-OutputFormat"));
			byte[] in = exchange.getRequestBody().readAllBytes();
			this.body.set(new String(in, StandardCharsets.UTF_8));
			if ("err".equals(this.mode)) {
				byte[] err = "bad request".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "text/plain");
				exchange.sendResponseHeaders(400, err.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(err);
				}
				return;
			}
			byte[] audio = "RIFFfake-audio".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
			exchange.sendResponseHeaders(200, audio.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(audio);
			}
		});
	}

	/** 构造指向 mock 的客户端。 */
	private AzureTtsClient newClient() {
		AiConfig cfg = AiConfig.builder().apiKey(SPEECH_KEY).baseUrl(this.baseUrl).build();
		return new AzureTtsClient(cfg);
	}

	/** 成功：SSML body、Ocp-Apim 头、X-Microsoft-OutputFormat 头、二进制音频。 */
	@Test
	public void testSynthesize() {
		AzureTtsClient client = newClient();
		TtsResponse resp = client.synthesize(TtsRequest.builder()
			.model("azure-tts").input("你好<世界>").voice(AzureModels.TTS_VOICE_XIAOXIAO)
			.responseFormat("mp3").build());
		assertTrue(resp.audioLength() > 0);
		assertEquals("mp3", resp.format());
		assertEquals(SPEECH_KEY, this.subKey.get());
		assertEquals("application/ssml+xml", this.contentType.get());
		assertEquals(AzureModels.TTS_OUTPUT_MP3, this.outFormat.get());
		String ssml = this.body.get();
		assertTrue(ssml.startsWith("<speak version='1.0' xml:lang='zh-CN'>"));
		assertTrue(ssml.contains("<voice name='zh-CN-XiaoxiaoNeural'>"));
		assertTrue(ssml.contains("你好&lt;世界&gt;"));
		assertTrue(ssml.endsWith("</voice></speak>"));
		client.close();
	}

	/** 400 错误映射为 AiException。 */
	@Test
	public void testError() {
		this.mode = "err";
		AzureTtsClient client = newClient();
		assertThrows(AiException.class, () -> client.synthesize(
			TtsRequest.of("azure-tts", "x", AzureModels.TTS_VOICE_YUNXI)));
		client.close();
	}

	/** name()。 */
	@Test
	public void testName() {
		assertEquals("azure-tts", newClient().name());
	}
}
