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

package com.sure.ai.gemini;

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
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.ImageRequest;

/**
 * {@link GeminiUtil} 静态入口测试。
 *
 * @author sureai
 */
public class GeminiUtilTest {

	private HttpServer server;
	private String baseUrl;

	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1beta";
		this.server.createContext("/", this::handle);
		this.server.start();
	}

	@After
	public void tearDown() {
		this.server.stop(0);
		GeminiUtil.resetRealtimeClient();
	}

	/** mock。 */
	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		byte[] out;
		if (path.endsWith(":generateContent")) {
			out = ("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"你好\"}],\"role\":\"model\"}}]}")
				.getBytes(StandardCharsets.UTF_8);
		}
		else if (path.endsWith(":embedContent")) {
			out = "{\"embedding\":{\"values\":[0.1,0.2,0.3]}}".getBytes(StandardCharsets.UTF_8);
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
		GeminiUtil.init("key");
		GeminiUtil.init(AiConfig.builder().apiKey("key").baseUrl(this.baseUrl).build());
		assertEquals("你好", GeminiUtil.chat("gemini", "hi").firstText());
		assertEquals("你好", GeminiUtil.chat(ChatRequest.builder().model("gemini")
			.messages(ChatMessage.user("hi")).build()).firstText());
		GeminiUtil.chatStream(ChatRequest.builder().model("gemini").messages(ChatMessage.user("hi")).build(),
			c -> { });
		assertEquals(3, GeminiUtil.embed("emb", "hi").embeddings().get(0).length, 0.0);
		assertEquals(3, GeminiUtil.embed(new EmbeddingRequest("emb", List.of("hi")))
			.embeddings().get(0).length, 0.0);
		assertNotNull(GeminiUtil.name());
	}

	/** image 便捷方法委托（dead server 触发异常）。 */
	@Test
	public void testImageDelegatesThrow() {
		GeminiUtil.init(AiConfig.builder().apiKey("k").baseUrl("http://127.0.0.1:1").build());
		assertThrows(Exception.class, () -> GeminiUtil.image("imagen", "a cat"));
		assertThrows(Exception.class, () -> GeminiUtil.image(
			ImageRequest.builder().model("imagen").prompt("a cat").build()));
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		var c = GeminiUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** GeminiModels 私有构造器不可实例化。 */
	@Test
	public void testModelsPrivateCtor() throws Exception {
		var c = GeminiModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}

	/** 环境变量懒加载：HOLDER 置空后经 SURE_AI_GEMINI_API_KEY/BASE_URL 构造客户端。 */
	@Test
	public void testLoadFromEnv() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			resetHolder();
			env.set("SURE_AI_GEMINI_API_KEY", "env-key");
			env.set("SURE_AI_GEMINI_BASE_URL", this.baseUrl);
			assertEquals("你好", GeminiUtil.chat("gemini", "hi").firstText());
			// baseUrl 为空 → 走默认地址分支（仅校验不抛异常）。
			resetHolder();
			env.set("SURE_AI_GEMINI_BASE_URL", null);
			assertNotNull(GeminiUtil.client());
		}
		finally {
			env.restore();
			resetHolder();
		}
	}

	/** realtimeClient 经环境变量懒加载构造（AiConfig.of(apiKey)）。 */
	@Test
	public void testRealtimeClientFromEnv() {
		EnvVars env = EnvVars.begin();
		try {
			GeminiUtil.resetRealtimeClient();
			env.set("SURE_AI_GEMINI_API_KEY", "rt-key");
			var listener = new com.sure.ai.client.realtime.RealtimeEventListener() {
				public void onTranscript(String text) {
				}

				public void onAudio(byte[] audio) {
				}

				public void onError(String err) {
				}

				public void onClose() {
				}

				public void onEvent(String type, String rawJson) {
				}

				public void onInterrupted() {
				}
			};
			GeminiRealtimeClient c = GeminiUtil.realtimeClient("gemini-live", listener);
			assertEquals("gemini-realtime", c.name());
		}
		finally {
			GeminiUtil.resetRealtimeClient();
			env.restore();
		}
	}

	/** 反射置空 HOLDER 单例，强制下次 client() 走 env 懒加载。 */
	private static void resetHolder() throws Exception {
		var f = GeminiUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		Object holder = f.get(null);
		var inst = holder.getClass().getDeclaredField("instance");
		inst.setAccessible(true);
		inst.set(holder, null);
	}
}
