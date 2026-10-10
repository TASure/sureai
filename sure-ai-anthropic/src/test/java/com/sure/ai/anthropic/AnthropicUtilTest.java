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

package com.sure.ai.anthropic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

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
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;

/**
 * {@link AnthropicUtil} 静态入口测试。
 *
 * @author sureai
 */
public class AnthropicUtilTest {

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
		byte[] out = ("{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],\"model\":\"claude\","
			+ "\"stop_reason\":\"end_turn\"}").getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(200, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	/** init 与便捷 chat 经 mock。 */
	@Test
	public void testConvenience() {
		AnthropicUtil.init("sk-ant");
		AnthropicUtil.init(AiConfig.builder().apiKey("sk").baseUrl(this.baseUrl).build());
		assertEquals("你好", AnthropicUtil.chat("claude", "hi").firstText());
		assertEquals("你好", AnthropicUtil.chat(ChatRequest.builder().model("claude")
			.messages(ChatMessage.user("hi")).build()).firstText());
		AnthropicUtil.chatStream(ChatRequest.builder().model("claude").messages(ChatMessage.user("hi")).build(),
			c -> { });
		assertNotNull(AnthropicUtil.name());
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		var c = AnthropicUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
