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

package com.sure.ai.cohere;

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

/**
 * {@link CohereUtil} 静态入口测试。
 *
 * @author sureai
 */
public class CohereUtilTest {

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
		CohereUtil.resetRerankClient();
	}

	/** mock。 */
	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		byte[] out;
		if ("/chat".equals(path)) {
			out = ("{\"id\":\"chat-1\",\"message\":{\"role\":\"assistant\","
				+ "\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}}").getBytes(StandardCharsets.UTF_8);
		}
		else if ("/embed".equals(path)) {
			out = "{\"id\":\"e1\",\"embeddings\":{\"float\":[[0.1,0.2,0.3]]}}"
				.getBytes(StandardCharsets.UTF_8);
		}
		else if (path.endsWith("/rerank")) {
			out = "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}".getBytes(StandardCharsets.UTF_8);
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

	/** init 与便捷 chat/embed 经 mock；rerank 委托 dead server 触发异常。 */
	@Test
	public void testConvenience() {
		EnvVars env = EnvVars.begin();
		try {
			env.set(CohereUtil.ENV_API_KEY, "cohere-key");
			CohereUtil.init(AiConfig.builder().apiKey("cohere-key").baseUrl(this.baseUrl).build());
			assertEquals("你好", CohereUtil.chat("command", "hi").firstText());
			assertEquals("你好", CohereUtil.chat(ChatRequest.builder().model("command")
				.messages(ChatMessage.user("hi")).build()).firstText());
			CohereUtil.chatStream(ChatRequest.builder().model("command")
				.messages(ChatMessage.user("hi")).build(), c -> { });
			assertEquals(3, CohereUtil.embed(new EmbeddingRequest("embed", List.of("hi")))
				.embeddings().get(0).length, 0.0);
			assertNotNull(CohereUtil.rerankClient());
		}
		finally {
			env.restore();
		}
	}

	/** env 分支：缺 key 抛 AiException。 */
	@Test
	public void testEnvMissingKey() throws Exception {
		var f = CohereUtil.class.getDeclaredField("MAIN");
		f.setAccessible(true);
		f.get(null).getClass().getMethod("reset").invoke(f.get(null));
		EnvVars env = EnvVars.begin();
		try {
			env.set(CohereUtil.ENV_API_KEY, null);
			assertThrows(AiException.class, CohereUtil::client);
		}
		finally {
			env.restore();
		}
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		var c = CohereUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
