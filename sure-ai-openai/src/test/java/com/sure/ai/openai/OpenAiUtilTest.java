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

package com.sure.ai.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.VideoRequest;

/**
 * {@link OpenAiUtil} 静态入口测试。
 *
 * @author sureai
 */
public class OpenAiUtilTest {

	private HttpServer server;
	private String baseUrl;

	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** mock。 */
	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		byte[] out;
		if (path.endsWith("/chat/completions")) {
			out = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}")
				.getBytes(StandardCharsets.UTF_8);
		}
		else if (path.endsWith("/embeddings")) {
			out = "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}".getBytes(StandardCharsets.UTF_8);
		}
		else {
			out = "{}".getBytes(StandardCharsets.UTF_8);
		}
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	/** init 与便捷 chat/embed 经 mock。 */
	@Test
	public void testConvenience() {
		OpenAiUtil.init("sk");
		OpenAiUtil.init(AiConfig.builder().apiKey("sk").baseUrl(this.baseUrl).build());
		assertEquals("你好", OpenAiUtil.chat("gpt", "hi").firstText());
		assertEquals("你好", OpenAiUtil.chat(ChatRequest.builder().model("gpt")
			.messages(ChatMessage.user("hi")).build()).firstText());
		OpenAiUtil.chatStream(ChatRequest.builder().model("gpt").messages(ChatMessage.user("hi")).build(),
			c -> { });
		assertEquals(3, OpenAiUtil.embed("emb", "hi").embeddings().get(0).length, 0.0);
		assertEquals(3, OpenAiUtil.embed(new EmbeddingRequest("emb", List.of("hi")))
			.embeddings().get(0).length, 0.0);
	}

	/** image/video/tts/stt 便捷方法委托（dead server 触发异常）。 */
	@Test
	public void testDelegatesThrow() {
		OpenAiUtil.init(AiConfig.builder().apiKey("sk").baseUrl("http://127.0.0.1:1").build());
		assertThrows(Exception.class, () -> OpenAiUtil.image("dall", "a cat"));
		assertThrows(Exception.class, () -> OpenAiUtil.image(
			ImageRequest.builder().model("dall").prompt("a cat").build()));
		assertThrows(Exception.class, () -> OpenAiUtil.video("sora", "a cat"));
		assertThrows(Exception.class, () -> OpenAiUtil.video(VideoRequest.of("sora", "a cat")));
		assertThrows(Exception.class, () -> OpenAiUtil.tts("tts", "你好", "spk"));
		assertThrows(Exception.class, () -> OpenAiUtil.tts(TtsRequest.of("tts", "你好", "spk")));
		assertThrows(Exception.class, () -> OpenAiUtil.stt("whisper", new byte[] { 1 }));
		assertThrows(Exception.class, () -> OpenAiUtil.stt(SttRequest.of("whisper", new byte[] { 1 })));
	}

	/** env 分支：缺 key 抛 AiException。 */
	@Test
	public void testEnvMissingKey() throws Exception {
		var f = OpenAiUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		f.get(null).getClass().getMethod("reset").invoke(f.get(null));
		EnvVars env = EnvVars.begin();
		try {
			env.set(OpenAiUtil.ENV_API_KEY, null);
			assertThrows(AiException.class, OpenAiUtil::client);
		}
		finally {
			env.restore();
		}
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		var c = OpenAiUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
