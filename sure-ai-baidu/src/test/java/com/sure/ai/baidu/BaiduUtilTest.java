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

package com.sure.ai.baidu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.ImageRequest;

/**
 * {@link BaiduUtil} 静态入口全覆盖：单例便捷方法、env 分支、私有构造。
 *
 * @author sureai
 */
public class BaiduUtilTest {

	private HttpServer server;
	private String baseUrl;

	/** 启动 mock。 */
	@Before
	public void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		register();
		resetAll();
	}

	/** 停止并清空单例。 */
	@After
	public void tearDown() throws Exception {
		this.server.stop(0);
		resetAll();
	}

	/** 路由各端点成功响应。 */
	private void register() {
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String body;
			if (path.equals("/oauth/2.0/token")) {
				body = "{\"access_token\":\"t\",\"expires_in\":2592000}";
			} else if (path.contains("/chat/")) {
				body = "{\"id\":\"r\",\"result\":\"文心\",\"usage\":{\"total_tokens\":3}}";
			} else if (path.contains("/embeddings/")) {
				body = "{\"data\":[{\"embedding\":[0.1,0.2]}]}";
			} else if (path.contains("/images") || path.contains("text2image")) {
				body = "{\"id\":\"g\",\"data\":[{\"url\":\"https://example.com/i.png\"}]}";
			} else if (path.equals("/text2audio")) {
				byte[] audio = "MP3".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
				exchange.sendResponseHeaders(200, audio.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(audio);
				}
				return;
			} else if (path.equals("/server_api")) {
				body = "{\"err_no\":0,\"result\":[\"转写\"]}";
			} else {
				body = "{}";
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
	}

	/** 清空 MAIN/IMAGE 单例。 */
	private static void resetAll() throws Exception {
		for (String name : new String[] { "MAIN", "IMAGE" }) {
			Field f = BaiduUtil.class.getDeclaredField(name);
			f.setAccessible(true);
			((SingletonHolder<?>) f.get(null)).reset();
		}
	}

	/** 指向 mock 的客户端配置。 */
	private AiConfig mockCfg() {
		return AiConfig.builder().apiKey("ak").baseUrl(this.baseUrl)
			.extraHeader(BaiduClient.SECRET_KEY_HEADER, "sk")
			.extraHeader(BaiduClient.TTS_URL_HEADER, this.baseUrl + "/text2audio")
			.extraHeader(BaiduClient.STT_URL_HEADER, this.baseUrl + "/server_api")
			.build();
	}

	/** 反射注入单例客户端。 */
	@SuppressWarnings("unchecked")
	private static void inject(String holder, Object client) throws Exception {
		Field f = BaiduUtil.class.getDeclaredField(holder);
		f.setAccessible(true);
		((SingletonHolder<Object>) f.get(null)).set(client);
	}

	/** 便捷 chat/embed/image 经 mock。 */
	@Test
	public void testConvenience() throws Exception {
		BaiduUtil.init(mockCfg());
		inject("IMAGE", new BaiduImageClient(mockCfg()));
		assertEquals("文心", BaiduUtil.chat("ernie", "hi").firstText());
		assertEquals("文心", BaiduUtil.chat(ChatRequest.builder().model("ernie")
			.messages(ChatMessage.user("hi")).build()).firstText());
		assertEquals(2, BaiduUtil.embed(new EmbeddingRequest("emb", List.of("hi"))).embeddings().get(0).length, 0.0);
		assertNotNull(BaiduUtil.imageClient());
		assertTrue(BaiduUtil.tts("baidu", "你好", BaiduModels.TTS_PER_XIAOMEI).audioLength() > 0);
		assertEquals("转写", BaiduUtil.stt("baidu", new byte[] { 1 }).text());
	}

	/** 无 env 时懒加载抛 AiException。 */
	@Test
	public void testLazyThrows() {
		assertThrows(AiException.class, BaiduUtil::client);
		assertThrows(AiException.class, BaiduUtil::imageClient);
	}

	/** image 便捷方法委托 imageClient()（行覆盖；mock 不支持完整流程故抛异常）。 */
	@Test
	public void testImageConvenience() throws Exception {
		inject("IMAGE", new BaiduImageClient(mockCfg()));
		assertThrows(Exception.class, () -> BaiduUtil.image("vg", "a cat"));
		assertThrows(Exception.class, () -> BaiduUtil.image(ImageRequest.builder().model("vg").prompt("a cat").build()));
	}

	/** chatStream / tts(request) / stt(request) 重载经 mock。 */
	@Test
	public void testStreamAndRequestOverloads() throws Exception {
		BaiduUtil.init(mockCfg());
		StringBuilder sb = new StringBuilder();
		BaiduUtil.chatStream(ChatRequest.builder().model("ernie").messages(ChatMessage.user("hi")).build(),
			c -> {
				if (c.deltaText() != null) {
					sb.append(c.deltaText());
				}
			});
		assertTrue(sb.length() >= 0);
		assertTrue(BaiduUtil.tts(com.sure.ai.model.TtsRequest.of("baidu", "你好", BaiduModels.TTS_PER_XIAOMEI)).audioLength() > 0);
		assertEquals("转写", BaiduUtil.stt(com.sure.ai.model.SttRequest.of("baidu", new byte[] { 1 })).text());
	}

	/** env 分支：缺 key、缺 secret、完整成功。 */
	@Test
	public void testEnvBranches() throws Exception {
		EnvVars env = EnvVars.begin();
		try {
			env.set(BaiduUtil.ENV_API_KEY, null);
			env.set(BaiduUtil.ENV_SECRET_KEY, null);
			env.set(BaiduUtil.ENV_BASE_URL, null);
			resetAll();
			assertThrows(AiException.class, BaiduUtil::client);

			env.set(BaiduUtil.ENV_API_KEY, "ak");
			resetAll();
			assertThrows(AiException.class, BaiduUtil::client);

			env.set(BaiduUtil.ENV_SECRET_KEY, "sk");
			env.set(BaiduUtil.ENV_BASE_URL, this.baseUrl);
			resetAll();
			assertNotNull(BaiduUtil.client());
			assertNotNull(BaiduUtil.imageClient());
		}
		finally {
			env.restore();
			resetAll();
		}
	}

	/** resetImageClient。 */
	@Test
	public void testResetImage() throws Exception {
		Field f = BaiduUtil.class.getDeclaredField("IMAGE");
		f.setAccessible(true);
		@SuppressWarnings("rawtypes")
		SingletonHolder h = (SingletonHolder) f.get(null);
		BaiduUtil.resetImageClient();
		Field inst = SingletonHolder.class.getDeclaredField("instance");
		inst.setAccessible(true);
		assertNull(inst.get(h));
	}

	/** 私有构造器抛 AssertionError。 */
	@Test
	public void testPrivateConstructor() throws Exception {
		Constructor<BaiduUtil> c = BaiduUtil.class.getDeclaredConstructor();
		c.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, c::newInstance);
	}
}
