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

package com.sure.ai.agent.tool.builtin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.internal.json.JsonObject;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link HttpTool} 边界补充测试（本地 {@link HttpServer} mock，零真实网络）。
 *
 * <p>覆盖默认请求头、参数级请求头、未知方法回退 GET、非法 URL、
 * 连接失败异常收敛与 Builder 校验分支。</p>
 */
public class HttpToolEdgeTest {

	private HttpServer server;
	private int port;

	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			String auth = exchange.getRequestHeaders().getFirst("X-Default");
			String extra = exchange.getRequestHeaders().getFirst("X-Extra");
			byte[] body = ("auth=" + auth + ";extra=" + extra)
				.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		this.server.start();
		this.port = this.server.getAddress().getPort();
	}

	@After
	public void tearDown() {
		this.server.stop(0);
	}

	private String url(String path) {
		return "http://127.0.0.1:" + this.port + path;
	}

	@Test
	public void testToToolFunctionDeclaresSchema() {
		assertNotNull(HttpTool.toToolFunction());
		assertEquals("http_request", HttpTool.toToolFunction().name());
	}

	@Test
	public void testDefaultHeadersApplied() {
		HttpTool tool = HttpTool.builder()
			.ssrfProtection(false)
			.defaultHeaders(Map.of("X-Default", "dv"))
			.timeout(Duration.ofSeconds(5))
			.build();
		JsonObject args = new JsonObject();
		args.put("url", url("/"));
		String result = tool.execute(args);
		assertTrue(result, result.contains("auth=dv"));
	}

	@Test
	public void testPerRequestHeadersAndUnknownMethod() {
		HttpTool tool = HttpTool.builder().ssrfProtection(false).build();
		JsonObject args = new JsonObject();
		args.put("url", url("/"));
		args.put("method", "DELETE"); // 未知方法回退 GET
		JsonObject headers = new JsonObject();
		headers.put("X-Extra", "ev");
		args.put("headers", headers);
		String result = tool.execute(args);
		assertTrue(result, result.contains("extra=ev"));
	}

	@Test
	public void testInvalidUrl() {
		HttpTool tool = HttpTool.builder().build();
		JsonObject args = new JsonObject();
		args.put("url", "http://[unclosed");
		String result = tool.execute(args);
		assertTrue(result, result.startsWith("Error: invalid url"));
	}

	@Test
	public void testConnectionFailure() {
		// 指向一个未监听端口（ssrf 关闭以绕过回环拦截，直接触发连接失败）
		HttpTool tool = HttpTool.builder().ssrfProtection(false).build();
		JsonObject args = new JsonObject();
		args.put("url", "http://127.0.0.1:1/");
		String result = tool.execute(args);
		assertTrue(result, result.startsWith("Error: http request failed"));
	}

	@Test
	public void testRequestInterruptedMidSend() throws Exception {
		// 本地服务器接受连接后不响应，挂起 send；60ms 后中断当前线程
		java.net.ServerSocket ss = new java.net.ServerSocket(0);
		int hangPort = ss.getLocalPort();
		Thread acceptor = new Thread(() -> {
			try {
				java.net.Socket s = ss.accept();
				Thread.sleep(5000);
			} catch (Exception e) {
				// 忽略
			}
		});
		acceptor.setDaemon(true);
		acceptor.start();

		HttpTool tool = HttpTool.builder().ssrfProtection(false).build();
		JsonObject args = new JsonObject();
		args.put("url", "http://127.0.0.1:" + hangPort + "/");
		Thread self = Thread.currentThread();
		java.util.concurrent.ScheduledExecutorService sched =
				java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
		sched.schedule(self::interrupt, 60, java.util.concurrent.TimeUnit.MILLISECONDS);
		try {
			String result = tool.execute(args);
			assertTrue(result, result.startsWith("Error: request interrupted"));
		} finally {
			sched.shutdownNow();
			ss.close();
		}
	}

	@Test
	public void testBuilderNullHeadersAndInvalidTimeout() {
		// null headers 不抛；非法 timeout 被忽略
		HttpTool tool = HttpTool.builder()
			.ssrfProtection(false)
			.defaultHeaders(null)
			.timeout(Duration.ofSeconds(-1))
			.maxResponseLength(-5)
			.build();
		JsonObject args = new JsonObject();
		args.put("url", url("/"));
		String result = tool.execute(args);
		assertTrue(result, result.startsWith("auth="));
	}
}
