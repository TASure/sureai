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

package com.sure.ai.ollama;

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
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.EmbeddingRequest;

/**
 * {@link OllamaUtil} 静态入口测试。
 *
 * @author sureai
 */
public class OllamaUtilTest {

	private HttpServer server;
	private String baseUrl;

	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
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
		if ("/api/chat".equals(path)) {
			out = ("{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}")
				.getBytes(StandardCharsets.UTF_8);
		}
		else if ("/api/embed".equals(path)) {
			out = "{\"embeddings\":[[0.1,0.2,0.3]]}".getBytes(StandardCharsets.UTF_8);
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
		OllamaUtil.init(AiConfig.builder().apiKey("ollama").baseUrl(this.baseUrl).build());
		assertEquals("你好", OllamaUtil.chat("llama", "hi").firstText());
		assertEquals("你好", OllamaUtil.chat(ChatRequest.builder().model("llama")
			.messages(ChatMessage.user("hi")).build()).firstText());
		OllamaUtil.chatStream(ChatRequest.builder().model("llama").messages(ChatMessage.user("hi")).build(),
			c -> { });
		assertEquals(3, OllamaUtil.embed("llama", "hi").embeddings().get(0).length, 0.0);
		assertEquals(3, OllamaUtil.embed(new EmbeddingRequest("llama", List.of("hi")))
			.embeddings().get(0).length, 0.0);
		assertNotNull(OllamaUtil.name());
	}

	/** init() 无参重载。 */
	@Test
	public void testInitNoArgs() {
		OllamaUtil.init();
		assertNotNull(OllamaUtil.client());
	}

	/** env 设置 baseUrl 时，loadFromEnv 指向该地址并可对话。 */
	@Test
	public void testLoadFromEnvWithBaseUrl() throws Exception {
		resetHolder();
		EnvVars env = EnvVars.begin();
		try {
			env.set("SURE_AI_OLLAMA_BASE_URL", this.baseUrl);
			assertEquals("你好", OllamaUtil.chat("llama", "hi").firstText());
		}
		finally {
			env.restore();
			resetHolder();
		}
	}

	/** env 未设置时，loadFromEnv 回退默认 localhost 地址（仅构造不触网）。 */
	@Test
	public void testLoadFromEnvDefaultBase() throws Exception {
		resetHolder();
		EnvVars env = EnvVars.begin();
		try {
			env.set("SURE_AI_OLLAMA_BASE_URL", null);
			assertNotNull(OllamaUtil.client());
		}
		finally {
			env.restore();
			resetHolder();
		}
	}

	/** 反射重置 HOLDER 单例。 */
	private static void resetHolder() throws Exception {
		var f = OllamaUtil.class.getDeclaredField("HOLDER");
		f.setAccessible(true);
		f.get(null).getClass().getMethod("reset").invoke(f.get(null));
	}

	/** Models 私有构造器不可实例化，常量可读。 */
	@Test
	public void testModelsConstructor() throws Exception {
		var c = OllamaModels.class.getDeclaredConstructor();
		c.setAccessible(true);
		var ex = assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
		assertTrue(ex.getCause() instanceof AssertionError);
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		var c = OllamaUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
