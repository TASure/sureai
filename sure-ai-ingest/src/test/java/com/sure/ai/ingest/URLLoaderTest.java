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

package com.sure.ai.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link URLLoader} 测试：本地 HttpServer mock，零真实网络。
 */
public class URLLoaderTest {

	private HttpServer server;
	private String baseUrl;
	private int status = 200;
	private byte[] body = new byte[0];
	private String contentType = "text/html; charset=utf-8";

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(body);
				}
			}
		});
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	@Test
	public void testDownloadHtml() {
		this.body = "<html><body><h1>Web</h1><p>page</p></body></html>".getBytes(StandardCharsets.UTF_8);
		URLLoader loader = URLLoader.createDefault();
		var docs = loader.load(URI.create(baseUrl + "/a/page.html"));
		assertEquals(1, docs.size());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("Web"));
		assertEquals("html", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
		assertEquals(contentType, docs.get(0).metadata().get(DocumentLoader.META_CONTENT_TYPE));
	}

	@Test
	public void testContentTypeFallback() {
		// 路径无扩展名，按 Content-Type 推断
		this.contentType = "text/plain; charset=utf-8";
		this.body = "plain body".getBytes(StandardCharsets.UTF_8);
		URLLoader loader = URLLoader.createDefault();
		var docs = loader.load(URI.create(baseUrl + "/download"));
		assertEquals(1, docs.size());
		assertEquals("plain body", docs.get(0).text());
		assertEquals("txt", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
	}

	@Test
	public void testHttpError() {
		this.status = 500;
		this.body = "boom".getBytes(StandardCharsets.UTF_8);
		URLLoader loader = URLLoader.createDefault();
		try {
			loader.load(URI.create(baseUrl + "/err"));
			throw new AssertionError("应抛出 IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("500"));
		}
	}

	@Test(expected = IllegalStateException.class)
	public void testNonHttpScheme() {
		URLLoader.createDefault().load(URI.create("ftp://example.com/x.txt"));
	}

	@Test(expected = IllegalStateException.class)
	public void testLoadPathUnsupported() {
		URLLoader.createDefault().load(java.nio.file.Path.of("/tmp/x.txt"));
	}
}
