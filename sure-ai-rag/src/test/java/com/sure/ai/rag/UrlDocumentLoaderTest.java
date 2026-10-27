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

package com.sure.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.rag.loader.UrlDocumentLoader;
import com.sure.ai.rag.model.Document;

/**
 * {@link UrlDocumentLoader} 测试：本地 HttpServer mock，零真实网络。
 */
public class UrlDocumentLoaderTest {

	private HttpServer server;
	private String baseUrl;
	private int status = 200;
	private String responseBody = "";
	private String contentType = "text/html; charset=utf-8";

	/** 启动 mock 服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(bytes);
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
	public void testLoadHtmlPage() {
		this.status = 200;
		this.responseBody = "<html><head><title>Test</title></head><body>"
				+ "<h1>Hello</h1><p>World</p>"
				+ "<script>alert(1)</script></body></html>";
		UrlDocumentLoader loader = new UrlDocumentLoader(URI.create(baseUrl + "/page"));
		List<Document> documents = loader.load();
		assertEquals(1, documents.size());
		Document doc = documents.get(0);
		String text = doc.text();
		assertTrue(text, text.contains("Hello"));
		assertTrue(text, text.contains("World"));
		assertTrue(text, text.contains("Test"));
		assertTrue(text, !text.contains("alert"));
		assertTrue(text, !text.contains("<"));
		assertEquals(baseUrl + "/page", doc.metadata().get(UrlDocumentLoader.META_SOURCE));
		assertNotNull(doc.metadata().get(UrlDocumentLoader.META_LOADED_AT));
		assertEquals(contentType, doc.metadata().get(UrlDocumentLoader.META_CONTENT_TYPE));
	}

	@Test
	public void testLoadHtmlEntities() {
		this.status = 200;
		this.responseBody = "<html><body><p>&amp; &lt; &gt; &quot; &#39; &nbsp; &#65;</p></body></html>";
		UrlDocumentLoader loader = new UrlDocumentLoader(URI.create(baseUrl + "/entities"));
		List<Document> documents = loader.load();
		String text = documents.get(0).text();
		assertTrue(text, text.contains("&"));
		assertTrue(text, text.contains("<"));
		assertTrue(text, text.contains(">"));
		assertTrue(text, text.contains("\""));
		assertTrue(text, text.contains("'"));
		// &#65; -> 'A'
		assertTrue(text, text.contains("A"));
	}

	@Test
	public void testHttpError() {
		this.status = 404;
		this.responseBody = "not found";
		UrlDocumentLoader loader = new UrlDocumentLoader(URI.create(baseUrl + "/missing"));
		try {
			loader.load();
			throw new AssertionError("应抛出 IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("404"));
		}
	}

	@Test
	public void testStringUrlConstructor() {
		this.status = 200;
		this.responseBody = "<html><body><p>ok</p></body></html>";
		UrlDocumentLoader loader = new UrlDocumentLoader(baseUrl + "/str");
		assertEquals(1, loader.load().size());
	}
}
