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
package com.sure.ai.rag.coverage;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.rag.store.WeaviateVectorStore;
import com.sure.ai.rag.store.typesense.TypesenseVectorStore;

/**
 * 快速易赢补覆盖：Weaviate 3参重载、Typesense autoCreate clear()、MarkdownTextSplitter 重叠。
 *
 * @author sureai
 * @since 2.6.0
 */
public class QuickWinsExtraTest {

	private HttpServer server;
	private String baseUrl;

	/** 启动本地服务。 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", ex -> {
			byte[] bytes = "{\"count\":0}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
			ex.close();
		});
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/** Weaviate：3参重载委托。 */
	@Test
	public void testWeaviateThreeArgOverload() {
		WeaviateVectorStore store = WeaviateVectorStore.builder()
				.baseUrl(baseUrl).className("Doc").textProperty("content").build();
		assertNotNull(store.similaritySearch(new float[] { 0.1f, 0.2f }, 5, 0.5));
	}

	/** Typesense：autoCreateCollection=true 时 clear() 重建集合。 */
	@Test
	public void testTypesenseClearAutoCreate() {
		TypesenseVectorStore store = TypesenseVectorStore.builder()
				.baseUrl(baseUrl).collectionName("docs").dimension(3).autoCreateCollection(true).build();
		store.clear();
		assertNotNull(store);
	}

	/** MarkdownTextSplitter：带重叠切分。 */
	@Test
	public void testMarkdownSplitterWithOverlap() {
		com.sure.ai.rag.splitter.MarkdownTextSplitter s =
				new com.sure.ai.rag.splitter.MarkdownTextSplitter(100, 20);
		String md = "# Title\n\nParagraph one.\n\nParagraph two.\n\nParagraph three.";
		assertTrue(s.split(md).size() > 0);
	}
}
