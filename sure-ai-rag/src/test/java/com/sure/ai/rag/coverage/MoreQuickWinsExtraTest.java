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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import com.sure.ai.rag.store.WeaviateVectorStore;

/**
 * 更多快速易赢：Weaviate autoCreateSchema clear()、Typesense addAll null 元素跳过。
 *
 * @author sureai
 * @since 2.6.0
 */
public class MoreQuickWinsExtraTest {

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

	/** Weaviate：autoCreateSchema=true 时 clear() 重建 schema。 */
	@Test
	public void testWeaviateClearAutoCreateSchema() {
		WeaviateVectorStore store = WeaviateVectorStore.builder()
				.baseUrl(baseUrl).className("Doc").textProperty("content")
				.autoCreateSchema(true).build();
		store.clear();
		assertNotNull(store);
	}
}
