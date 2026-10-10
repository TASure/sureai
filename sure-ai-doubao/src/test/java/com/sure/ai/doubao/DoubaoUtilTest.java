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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
 * {@link DoubaoUtil} 静态入口测试：env 分支、私有构造器、便捷方法委托。
 *
 * @author sureai
 */
public class DoubaoUtilTest {

	private HttpServer server;
	private String baseUrl;

	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/api/v3";
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	@After
	public void tearDown() {
		this.server.stop(0);
		DoubaoUtil.resetVideoClient();
		DoubaoUtil.resetTtsClient();
		DoubaoUtil.resetSttClient();
		DoubaoUtil.resetRealtimeClient();
	}

	/** mock 响应。 */
	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		byte[] out;
		if (path.endsWith("/chat/completions")) {
			out = ("{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"message\":{\"role\":\"assistant\","
				+ "\"content\":\"你好\"},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":1}}")
				.getBytes(StandardCharsets.UTF_8);
		}
		else if (path.endsWith("/embeddings")) {
			out = ("{\"data\":[{\"embedding\":[0.1,0.2]}],\"usage\":{\"total_tokens\":1}}")
				.getBytes(StandardCharsets.UTF_8);
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
		return AiConfig.builder().apiKey("ark-key").baseUrl(this.baseUrl).build();
	}

	/** init(apiKey)。 */
	@Test
	public void testInitByKey() {
		DoubaoUtil.init("ark-key");
		assertNotNull(DoubaoUtil.client());
	}

	/** 便捷 chat / embed 经 mock。 */
	@Test
	public void testConvenience() {
		DoubaoUtil.init(cfg());
		assertEquals("你好", DoubaoUtil.chat("ep-1", "hi").firstText());
		assertEquals("你好", DoubaoUtil.chat(ChatRequest.builder().model("ep-1")
			.messages(ChatMessage.user("hi")).build()).firstText());
		DoubaoUtil.chatStream(ChatRequest.builder().model("ep-1").messages(ChatMessage.user("hi")).build(),
			c -> { });
		assertEquals(2, DoubaoUtil.embed(new EmbeddingRequest("ep-1", List.of("hi")))
			.embeddings().get(0).length, 0.0);
	}

	/** 视频/TTS/STT 便捷方法委托对应客户端（dead server 触发异常，行仍执行）。 */
	@Test
	public void testDelegatesThrow() throws Exception {
		resetHolder();
		EnvVars env = EnvVars.begin();
		try {
			env.set(DoubaoUtil.ENV_API_KEY, "env-key");
			AiConfig dead = AiConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:1").build();
			DoubaoUtil.init(dead);
			assertThrows(Exception.class, () -> DoubaoUtil.video("m", "x"));
			assertThrows(Exception.class, () -> DoubaoUtil.video(
				com.sure.ai.model.VideoRequest.of("m", "x")));
			assertThrows(Exception.class, () -> DoubaoUtil.tts("m", "你好", "spk"));
			assertThrows(Exception.class, () -> DoubaoUtil.tts(
				TtsRequest.of("m", "你好", "spk")));
			assertThrows(Exception.class, () -> DoubaoUtil.stt("m", new byte[] { 1 }));
			assertThrows(Exception.class, () -> DoubaoUtil.stt(SttRequest.of("m", new byte[] { 1 })));
			assertNotNull(DoubaoUtil.ttsClient());
			assertNotNull(DoubaoUtil.sttClient());
			assertNotNull(DoubaoUtil.videoClient());
		}
		finally {
			env.restore();
			resetHolder();
		}
	}

	/** 经反射清空 HOLDER 单例（无公开 resetClient）。 */
	private static void resetHolder() throws Exception {
		java.lang.reflect.Field f = DoubaoUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		Object holder = f.get(null);
		holder.getClass().getMethod("reset").invoke(holder);
	}

	/** env 分支：缺 key 抛 AiException。 */
	@Test
	public void testEnvMissingKey() throws Exception {
		resetHolder();
		EnvVars env = EnvVars.begin();
		try {
			env.set(DoubaoUtil.ENV_API_KEY, null);
			assertThrows(AiException.class, DoubaoUtil::client);
			assertThrows(AiException.class, DoubaoUtil::videoClient);
			assertThrows(AiException.class, DoubaoUtil::ttsClient);
			assertThrows(AiException.class, DoubaoUtil::sttClient);
		}
		finally {
			env.restore();
		}
	}

	/** env 分支：key 与 baseUrl 均设置。 */
	@Test
	public void testEnvWithBase() throws Exception {
		resetHolder();
		EnvVars env = EnvVars.begin();
		try {
			env.set(DoubaoUtil.ENV_API_KEY, "env-key");
			env.set(DoubaoUtil.ENV_BASE_URL, this.baseUrl);
			assertNotNull(DoubaoUtil.client());
			assertEquals("你好", DoubaoUtil.chat("ep", "hi").firstText());
		}
		finally {
			env.restore();
			resetHolder();
		}
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		var c = DoubaoUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** reset 方法。 */
	@Test
	public void testResets() {
		DoubaoUtil.resetVideoClient();
		DoubaoUtil.resetTtsClient();
		DoubaoUtil.resetSttClient();
		DoubaoUtil.resetRealtimeClient();
		assertTrue(true);
	}
}
