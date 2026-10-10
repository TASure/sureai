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
package com.sure.ai.rag.loader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
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

import com.sure.ai.rag.model.Document;

/**
 * {@link UrlDocumentLoader} 单元测试：本地 {@link HttpServer} mock 覆盖成功加载、非 2xx、
 * 空正文、HTML 去标签与实体解码、非法 URL、连接拒绝，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class UrlDocumentLoaderTest {

	private HttpServer server;
	private String url;
	private volatile int status = 200;
	private volatile String html = "<html><body><p>你好 &amp; 世界</p></body></html>";

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
			ex.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
			ex.close();
		});
		this.server.start();
		this.url = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/page";
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** 成功加载：去标签、实体解码、元数据。 */
	@Test
	public void testLoadSuccess() {
		List<Document> docs = new UrlDocumentLoader(url).load();
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("你好"));
		assertTrue(docs.get(0).text().contains("世界"));
		assertEquals(url, docs.get(0).metadata().get(UrlDocumentLoader.META_SOURCE));
		assertTrue(docs.get(0).metadata().containsKey(UrlDocumentLoader.META_LOADED_AT));
	}

	/** script/style 块与标签去除；数字实体解码。 */
	@Test
	public void testExtractBlocksAndEntities() {
		this.html = "<html><script>var a=1;</script><style>body{}</style>"
				+ "<h1>标题&#22823;&#23398;</h1><p>文本&nbsp;内容&#x4E2D;</p></html>";
		List<Document> docs = new UrlDocumentLoader(URI.create(url)).load();
		String text = docs.get(0).text();
		assertTrue(text.contains("标题"));
		assertTrue(text.contains("大学"));
		assertTrue(!text.contains("var a"));
		assertTrue(!text.contains("body{"));
	}

	/** 非 2xx → IllegalStateException。 */
	@Test
	public void testNon2xxThrows() {
		this.status = 500;
		assertThrows(IllegalStateException.class, () -> new UrlDocumentLoader(url).load());
	}

	/** 空正文 → 返回空列表。 */
	@Test
	public void testEmptyBodyReturnsEmpty() {
		this.html = "";
		assertTrue(new UrlDocumentLoader(url).load().isEmpty());
	}

	/** 非法 URL → IllegalArgumentException。 */
	@Test
	public void testIllegalUrlThrows() {
		assertThrows(IllegalArgumentException.class, () -> new UrlDocumentLoader("http://[bad"));
	}

	/** 连接拒绝 → IllegalStateException。 */
	@Test
	public void testConnectionRefusedThrows() throws IOException {
		try (java.net.ServerSocket dead = new java.net.ServerSocket(0)) {
			int port = dead.getLocalPort();
			server.stop(0);
			assertThrows(IllegalStateException.class,
					() -> new UrlDocumentLoader("http://127.0.0.1:" + port).load());
		}
	}
}
