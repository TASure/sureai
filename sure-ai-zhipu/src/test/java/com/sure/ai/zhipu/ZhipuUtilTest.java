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

package com.sure.ai.zhipu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.client.AiConfig;
import com.sure.ai.client.SingletonHolder;
import com.sure.ai.exception.AiException;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.EmbeddingRequest;

/**
 * {@link ZhipuUtil} 静态入口测试：反射注入指向 mock 的客户端。
 *
 * @author sureai
 * @since 0.1.0
 */
public class ZhipuUtilTest {

	private HttpServer server;
	private String baseUrl;

	/** 启动本地服务并注册 chat/embeddings 响应。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.baseUrl = "http://127.0.0.1:" + port + "/api/paas/v4";
		this.server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			byte[] in = exchange.getRequestBody().readAllBytes();
			String req = new String(in, StandardCharsets.UTF_8);
			byte[] bytes;
			if (path.endsWith("/embeddings")) {
				String body = "{\"model\":\"embedding-3\",\"data\":[{\"embedding\":[0.1,0.2]}],"
					+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0,\"total_tokens\":1}}";
				bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
			} else if (req.contains("\"stream\":true")) {
				String sse = "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"ok\"},\"index\":0}]}\n\n"
					+ "data: {\"id\":\"c\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
					+ "data: [DONE]\n\n";
				bytes = sse.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			} else {
				String body = "{\"id\":\"c\",\"choices\":[{\"index\":0,"
					+ "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
				bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
			}
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		setSingleton(null);
	}

	/** 停止服务并清理单例。 */
	@After
	public void tearDown() {
		this.server.stop(0);
		setSingleton(null);
	}

	/** 反射设置单例字段。 */
	@SuppressWarnings("unchecked")
	private static void setSingleton(ZhipuClient c) {
		try {
			Field f = ZhipuUtil.class.getDeclaredField("HOLDER");
			f.setAccessible(true);
			SingletonHolder<ZhipuClient> holder = (SingletonHolder<ZhipuClient>) f.get(null);
			if (c == null) {
				holder.reset();
			} else {
				holder.set(c);
			}
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 反射注入指向 mock 的客户端。 */
	private void injectMockClient() {
		AiConfig cfg = AiConfig.builder().apiKey("id.secret").baseUrl(this.baseUrl).build();
		setSingleton(new ZhipuClient(cfg));
	}

	/** init(String) 构造可工作的单例（不触网）。 */
	@Test
	public void testInitByKey() {
		ZhipuUtil.init("id.secret");
		assertNotNull(ZhipuUtil.client());
	}

	/** init(AiConfig) 构造可工作的单例（不触网）。 */
	@Test
	public void testInitByConfig() {
		ZhipuUtil.init(AiConfig.builder().apiKey("id.secret").baseUrl(this.baseUrl).build());
		assertNotNull(ZhipuUtil.client());
	}

	/** 便捷 chat(model, prompt)。 */
	@Test
	public void testConvenienceChat() {
		injectMockClient();
		ChatResponse resp = ZhipuUtil.chat("glm-4-flash", "hi");
		assertEquals("ok", resp.firstText());
	}

	/** chat(ChatRequest)。 */
	@Test
	public void testChatRequest() {
		injectMockClient();
		ChatResponse resp = ZhipuUtil.chat(ChatRequest.builder().model("glm-4")
			.messages(ChatMessage.user("hi")).build());
		assertEquals("ok", resp.firstText());
	}

	/** chatStream。 */
	@Test
	public void testChatStream() {
		injectMockClient();
		StringBuilder sb = new StringBuilder();
		ZhipuUtil.chatStream(ChatRequest.builder().model("glm-4").messages(ChatMessage.user("hi")).build(),
			chunk -> {
				if (chunk.deltaText() != null) {
					sb.append(chunk.deltaText());
				}
			});
		assertEquals("ok", sb.toString());
	}

	/** embed。 */
	@Test
	public void testEmbed() {
		injectMockClient();
		assertEquals(1, ZhipuUtil.embed(new EmbeddingRequest("embedding-3", java.util.List.of("hi")))
			.embeddings().size());
	}

	/** 未初始化且环境变量缺失时，client() 抛 AiException。 */
	@Test
	public void testClientMissingEnv() {
		// 仅当环境变量未配置时才断言抛错；已配置时不应失败
		if (System.getenv(ZhipuUtil.ENV_API_KEY) == null) {
			try {
				ZhipuUtil.client();
			} catch (AiException ok) {
				return;
			}
			throw new AssertionError("expected AiException when env not set");
		}
		assertNotNull(ZhipuUtil.client());
	}
}
