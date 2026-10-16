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

package com.sure.ai.qwen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.TtsRequest;

/**
 * {@link QwenUtil} 静态入口测试：env 分支、私有构造器、便捷方法委托。
 *
 * @author sureai
 */
public class QwenUtilTest {

	private HttpServer server;
	private String baseUrl;

	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/compatible-mode/v1";
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	@After
	public void tearDown() {
		this.server.stop(0);
		QwenUtil.resetImageClient();
		QwenUtil.resetVideoClient();
		QwenUtil.resetRerankClient();
		QwenUtil.resetRealtimeClient();
		resetHolder();
	}

	/** 经反射清空 HOLDER。 */
	private static void resetHolder() {
		try {
			var f = QwenUtil.class.getDeclaredField("HOLDER");
			f.setAccessible(true);
			f.get(null).getClass().getMethod("reset").invoke(f.get(null));
		}
		catch (ReflectiveOperationException ignored) {
			// 忽略
		}
	}

	/** mock 响应。 */
	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		byte[] out;
		if (path.endsWith("/chat/completions")) {
			out = ("{\"id\":\"c1\",\"choices\":[{\"message\":{\"role\":\"assistant\","
				+ "\"content\":\"你好\"}}]}").getBytes(StandardCharsets.UTF_8);
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

	/** 指向 mock 的配置。 */
	private AiConfig cfg() {
		return AiConfig.builder().apiKey("dash-key").baseUrl(this.baseUrl).build();
	}

	/** init(apiKey) 与便捷 chat/embed/tts/stt 经 mock。 */
	@Test
	public void testConvenience() {
		QwenUtil.init("dash-key");
		QwenUtil.init(cfg());
		assertEquals("你好", QwenUtil.chat("qwen", "hi").firstText());
		assertEquals("你好", QwenUtil.chat(ChatRequest.builder().model("qwen")
			.messages(ChatMessage.user("hi")).build()).firstText());
		QwenUtil.chatStream(ChatRequest.builder().model("qwen").messages(ChatMessage.user("hi")).build(),
			c -> { });
		assertEquals(3, QwenUtil.embed("text-emb", "hi").embeddings().get(0).length, 0.0);
		assertEquals(3, QwenUtil.embed(new EmbeddingRequest("text-emb", List.of("hi")))
			.embeddings().get(0).length, 0.0);
	}

	/** image/video/rerank 便捷方法委托对应客户端（dead server 触发异常）。 */
	@Test
	public void testDelegatesThrow() {
		EnvVars env = EnvVars.begin();
		try {
			env.set(QwenUtil.ENV_API_KEY, "dash-key");
			QwenUtil.init(AiConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:1").build());
			assertThrows(Exception.class, () -> QwenUtil.image("wanx", "a cat"));
			assertThrows(Exception.class, () -> QwenUtil.image(
				com.sure.ai.model.ImageRequest.builder().model("wanx").prompt("a cat").build()));
			assertThrows(Exception.class, () -> QwenUtil.video("wan2", "a cat"));
			assertThrows(Exception.class, () -> QwenUtil.video(
				com.sure.ai.model.VideoRequest.of("wan2", "a cat")));
			assertThrows(Exception.class, () -> QwenUtil.rerank("q", List.of("d1")));
			assertThrows(Exception.class, () -> QwenUtil.rerank(
				com.sure.ai.model.RerankRequest.builder().model("r").query("q").documents(List.of("d1")).build()));
			assertThrows(Exception.class, () -> QwenUtil.tts("cosy", "你好", "spk"));
			assertThrows(Exception.class, () -> QwenUtil.tts(TtsRequest.of("cosy", "你好", "spk")));
			assertThrows(Exception.class, () -> QwenUtil.stt("asr", new byte[] { 1 }));
			assertThrows(Exception.class, () -> QwenUtil.stt(SttRequest.of("asr", new byte[] { 1 })));
			assertNotNull(QwenUtil.imageClient());
			assertNotNull(QwenUtil.videoClient());
			assertNotNull(QwenUtil.rerankClient());
		}
		finally {
			env.restore();
		}
	}

	/** env 分支：缺 key 抛 AiException。 */
	@Test
	public void testEnvMissingKey() {
		resetHolder();
		EnvVars env = EnvVars.begin();
		try {
			env.set(QwenUtil.ENV_API_KEY, null);
			assertThrows(AiException.class, QwenUtil::client);
			assertThrows(AiException.class, QwenUtil::imageClient);
			assertThrows(AiException.class, QwenUtil::videoClient);
			assertThrows(AiException.class, QwenUtil::rerankClient);
		}
		finally {
			env.restore();
		}
	}

	/** buildConfig：baseUrl 分支。 */
	@Test
	public void testBuildConfig() {
		assertNotNull(QwenUtil.buildConfig("k", "http://x"));
		assertNotNull(QwenUtil.buildConfig("k", null));
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		var c = QwenUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
