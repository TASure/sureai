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

package com.sure.ai.client.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sure.ai.exception.AiException;

/**
 * 基于 JDK {@link HttpClient} 的默认 WebSocket 连接器。
 *
 * <p>生产环境下各平台实时客户端通过本类完成真实建连：在握手阶段把构造时携带的
 * 鉴权头（如 {@code Authorization: Bearer <apiKey>}）写入 {@link WebSocket.Builder}。
 * 测试时改用 {@code FakeRealtimeConnector} 注入，实现零真实网络。</p>
 *
 * <p>本类持有一个共享的 {@link HttpClient}（及其专用 Executor），多次 {@link #connect}
 * 复用同一实例；用完后应调用 {@link #close()} 关闭 Executor，避免线程/连接泄漏。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class DefaultRealtimeConnector implements RealtimeConnector, AutoCloseable {

	/** 握手阶段附加的请求头（不可变拷贝）。 */
	private final Map<String, String> headers;

	/** HttpClient 的专用 Executor（close 时关闭）。 */
	private final ExecutorService executor;

	/** 共享的 HttpClient（懒创建后复用）。 */
	private final HttpClient httpClient;

	/** 是否已关闭。 */
	private volatile boolean closed;

	/**
	 * 构造连接器。
	 *
	 * @param headers 握手请求头，可为 null（无额外头）
	 */
	public DefaultRealtimeConnector(Map<String, String> headers) {
		this.headers = headers == null ? Map.of() : Map.copyOf(headers);
		this.executor = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "sureai-websocket-connector");
			t.setDaemon(true);
			return t;
		});
		this.httpClient = HttpClient.newBuilder().executor(this.executor).build();
	}

	@Override
	public WebSocket connect(URI uri, WebSocket.Listener listener) {
		if (this.closed) {
			throw new AiException("connector is closed");
		}
		try {
			WebSocket.Builder builder = this.httpClient.newWebSocketBuilder();
			for (Map.Entry<String, String> e : this.headers.entrySet()) {
				builder.header(e.getKey(), e.getValue());
			}
			return builder.buildAsync(uri, listener).get();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("websocket connect interrupted", ex);
		} catch (java.util.concurrent.ExecutionException ex) {
			throw new AiException("websocket connect failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * 关闭共享的 HttpClient Executor，释放后台线程。
	 */
	@Override
	public void close() {
		this.closed = true;
		this.executor.shutdownNow();
	}
}
