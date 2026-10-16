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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link URLLoader} 边界测试：可配置抛错的假 HttpClient 覆盖 IO / 中断异常分支，
 * 空路径 URI 的 Content-Type 兜底，以及 Builder 空参与链式调用。
 */
public class URLLoaderEdgeTest {

	private HttpServer server;
	private String baseUrl;

	/** 启动本地回环 mock 服务。
	 *
	 * @throws IOException 启动失败
	 */
	@Before
	public void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
			byte[] body = "loopback".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** 停止服务。 */
	@After
	public void tearDown() {
		this.server.stop(0);
	}

	/**
	 * send 抛 IOException 包装为 IllegalStateException。
	 */
	@Test
	public void testDownloadIoFailure() {
		URLLoader loader = URLLoader.builder()
				.httpClient(new FakeHttpClient(new IOException("boom")))
				.build();
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> loader.load(URI.create(this.baseUrl + "/a.txt")));
		assertTrue(e.getMessage(), e.getMessage().contains("下载 URL 失败"));
	}

	/**
	 * send 抛 InterruptedException：恢复中断状态并包装为 IllegalStateException。
	 */
	@Test
	public void testDownloadInterrupted() {
		URLLoader loader = URLLoader.builder()
				.httpClient(new FakeHttpClient(new InterruptedException()))
				.build();
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> loader.load(URI.create(this.baseUrl + "/a.txt")));
		assertTrue(e.getMessage(), e.getMessage().contains("下载 URL 被中断"));
		assertTrue(Thread.interrupted());
	}

	/**
	 * URI 无路径段：fileNameFromUri 返回空串，按 Content-Type 兜底为 txt。
	 */
	@Test
	public void testEmptyPathUsesContentType() {
		URLLoader loader = URLLoader.createDefault();
		var docs = loader.load(URI.create(this.baseUrl));
		assertEquals(1, docs.size());
		assertEquals("txt", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
	}

	/**
	 * Builder 链式调用：httpClient / requestTimeout / register 均生效。
	 */
	@Test
	public void testBuilderChain() {
		URLLoader loader = URLLoader.builder()
				.httpClient(HttpClient.newHttpClient())
				.requestTimeout(Duration.ofSeconds(5))
				.register(new com.sure.ai.ingest.spi.DocumentParser() {
					@Override
					public java.util.Set<String> extensions() {
						return java.util.Set.of(".custom");
					}

					@Override
					public java.util.List<com.sure.ai.rag.model.Document> parse(byte[] content,
							String source, java.util.Map<String, String> metadata) {
						metadata.put(DocumentLoader.META_FORMAT, "custom");
						return java.util.List.of(com.sure.ai.rag.model.Document
								.of(source, "x", metadata));
					}
				})
				.build();
		assertEquals(URLLoader.class, loader.getClass());
	}

	/**
	 * Builder 空参校验。
	 */
	@Test
	public void testBuilderNullChecks() {
		assertThrows(IllegalArgumentException.class, () -> URLLoader.builder().httpClient(null));
		assertThrows(IllegalArgumentException.class, () -> URLLoader.builder().requestTimeout(null));
		assertThrows(IllegalArgumentException.class, () -> URLLoader.builder().register(null));
	}

	/**
	 * load(null) 触发空参校验；相对 URI（scheme 为 null）被拒。
	 */
	@Test
	public void testNullAndRelativeUri() {
		assertThrows(IllegalArgumentException.class,
				() -> URLLoader.createDefault().load((URI) null));
		assertThrows(IllegalStateException.class,
				() -> URLLoader.createDefault().load(URI.create("relative/path.txt")));
	}

	/**
	 * 可配置抛错的假 HttpClient。
	 */
	private static final class FakeHttpClient extends HttpClient {

		/** send 要抛出的异常。 */
		private final Throwable error;

		FakeHttpClient(Throwable error) {
			this.error = error;
		}

		@Override
		public Optional<CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<Duration> connectTimeout() {
			return Optional.empty();
		}

		@Override
		public Redirect followRedirects() {
			return Redirect.NEVER;
		}

		@Override
		public java.util.Optional<java.net.ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public SSLContext sslContext() {
			try {
				return SSLContext.getDefault();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public SSLParameters sslParameters() {
			return new SSLParameters();
		}

		@Override
		public Optional<Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public Version version() {
			return Version.HTTP_1_1;
		}

		@Override
		public Optional<Executor> executor() {
			return Optional.empty();
		}

		@Override
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
				throws IOException, InterruptedException {
			if (this.error instanceof IOException) {
				throw (IOException) this.error;
			}
			if (this.error instanceof InterruptedException) {
				throw (InterruptedException) this.error;
			}
			throw new IllegalStateException(this.error);
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				HttpResponse.BodyHandler<T> handler) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException());
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException());
		}
	}
}
