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

package com.sure.ai.mcp.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;
import com.sure.ai.mcp.transport.McpTransport;

/**
 * 测试用管道客户端传输：与 {@link StdioMcpServerTransport} 的注入流对接，
 * 让 {@link com.sure.ai.mcp.McpClient} 能在 JVM 内完成握手回环（零网络）。
 *
 * @author sureai
 * @since 1.5.0
 */
final class PipeMcpTransport implements McpTransport {

	private final OutputStream out;
	private final ConcurrentHashMap<Long, CompletableFuture<McpResponse>> pending = new ConcurrentHashMap<>();
	private final Duration timeout = Duration.ofSeconds(15);
	private volatile boolean open = true;

	PipeMcpTransport(InputStream in, OutputStream out) {
		this.out = out;
		Thread t = new Thread(() -> readLoop(in), "pipe-client-reader");
		t.setDaemon(true);
		t.start();
	}

	private void readLoop(InputStream in) {
		BufferedReader br = new BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
		try {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				JsonElement el = Json.parse(line);
				if (!el.isObject()) {
					continue;
				}
				JsonObject o = el.getAsJsonObject();
				if (o.has("id") && (o.has("result") || o.has("error"))) {
					McpResponse r = McpResponse.fromJson(o);
					if (r.id() != null) {
						CompletableFuture<McpResponse> f = this.pending.remove(r.id());
						if (f != null) {
							f.complete(r);
						}
					}
				}
			}
		} catch (IOException ex) {
			// 对端关闭
		} finally {
			this.open = false;
			this.pending.values().forEach(f -> f.completeExceptionally(
				new java.io.IOException("pipe closed")));
			this.pending.clear();
		}
	}

	@Override
	public McpResponse sendRequest(McpRequest request) {
		CompletableFuture<McpResponse> f = new CompletableFuture<>();
		this.pending.put(request.id(), f);
		try {
			write(Json.stringify(request.toJson()));
			return f.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (Exception ex) {
			this.pending.remove(request.id());
			throw new RuntimeException("pipe request failed", ex);
		}
	}

	@Override
	public void sendNotification(McpNotification notification) {
		try {
			write(Json.stringify(notification.toJson()));
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
	}

	private void write(String line) throws IOException {
		synchronized (this.out) {
			this.out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			this.out.flush();
		}
	}

	@Override
	public void close() {
		this.open = false;
	}

	@Override
	public boolean isOpen() {
		return this.open;
	}
}
