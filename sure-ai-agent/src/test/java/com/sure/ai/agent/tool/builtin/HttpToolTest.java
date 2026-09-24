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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sure.ai.internal.json.JsonObject;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link HttpTool} 测试：用本地 {@link HttpServer} mock，零真实网络。
 */
public class HttpToolTest {

	private HttpServer server;
	private int port;

	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			byte[] body;
			if ("POST".equals(exchange.getRequestMethod())) {
				String reqBody = new String(exchange.getRequestBody().readAllBytes(),
					StandardCharsets.UTF_8);
				body = ("GOT:" + reqBody).getBytes(StandardCharsets.UTF_8);
			} else if ("/big".equals(exchange.getRequestURI().getPath())) {
				body = "a".repeat(10000).getBytes(StandardCharsets.UTF_8);
			} else {
				body = "hello-world".getBytes(StandardCharsets.UTF_8);
			}
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
	public void testGet() {
		JsonObject args = new JsonObject();
		args.put("url", url("/get"));
		String result = HttpTool.builder().build().execute(args);
		assertEquals("hello-world", result);
	}

	@Test
	public void testPost() {
		JsonObject args = new JsonObject();
		args.put("url", url("/post"));
		args.put("method", "POST");
		args.put("body", "post-data");
		String result = HttpTool.builder().build().execute(args);
		assertEquals("GOT:post-data", result);
	}

	@Test
	public void testTruncation() {
		JsonObject args = new JsonObject();
		args.put("url", url("/big"));
		String result = HttpTool.builder().maxResponseLength(100).build().execute(args);
		assertTrue(result, result.endsWith("...[truncated]"));
		assertTrue(result, result.length() <= 100 + "...[truncated]".length());
	}

	@Test
	public void testInvalidUrl() {
		JsonObject args = new JsonObject();
		args.put("url", "ftp://example.com/file");
		String result = HttpTool.builder().build().execute(args);
		assertTrue(result, result.startsWith("Error"));
	}

	@Test
	public void testMissingUrl() {
		JsonObject args = new JsonObject();
		String result = HttpTool.builder().build().execute(args);
		assertTrue(result, result.startsWith("Error"));
	}
}
