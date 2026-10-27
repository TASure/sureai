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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.rag.model.Document;

/**
 * 文档加载器补覆盖测试：{@link UrlDocumentLoader} 双参构造器、uri 访问器、被中断异常分支、
 * 非法数字实体解码兜底；{@link TxtDocumentLoader} 文件/输入流读取失败异常分支，零真实网络。
 *
 * @author sureai
 * @since 2.6.0
 */
public class LoaderCoverageExtraTest {

	private HttpServer server;
	private String url;
	private volatile int status = 200;
	private volatile String html = "<html><body><p>正文</p></body></html>";

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
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

	/** 双参构造器（URI + 自定义 HttpClient）。 */
	@Test
	public void testUrlLoaderTwoArgCtor() {
		UrlDocumentLoader loader = new UrlDocumentLoader(URI.create(url), HttpClient.newHttpClient());
		assertEquals(URI.create(url), loader.uri());
		assertEquals(1, loader.load().size());
	}

	/** 数字实体超出 int 范围 → parseInt 失败，原样保留不抛异常。 */
	@Test
	public void testUrlLoaderMalformedNumericEntity() {
		this.html = "<p>a &#999999999999; b</p>";
		Document doc = new UrlDocumentLoader(url).load().get(0);
		assertTrue(doc.text().contains("a"));
	}

	/** 线程中断状态预置 → load 抛 IllegalStateException 并恢复中断位。 */
	@Test
	public void testUrlLoaderInterrupted() {
		Thread.currentThread().interrupt();
		try {
			assertThrows(IllegalStateException.class, () -> new UrlDocumentLoader(url).load());
		} finally {
			Thread.interrupted();
		}
	}

	/** 输入流读取失败 → IllegalStateException。 */
	@Test
	public void testTxtLoaderInputStreamReadFails() {
		InputStream broken = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("boom");
			}
		};
		assertThrows(IllegalStateException.class, () -> new TxtDocumentLoader(broken, "src").load());
	}

	/** 读取目录（readAllBytes 抛 IOException）→ IllegalStateException。 */
	@Test
	public void testTxtLoaderReadDirectoryFails() throws IOException {
		Path dir = Files.createTempDirectory("rag-txt");
		try {
			assertThrows(IllegalStateException.class, () -> new TxtDocumentLoader(dir, StandardCharsets.UTF_8).load());
		} finally {
			Files.deleteIfExists(dir);
		}
	}
}
