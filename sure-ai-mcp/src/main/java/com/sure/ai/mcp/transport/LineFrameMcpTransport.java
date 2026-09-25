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

package com.sure.ai.mcp.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;

/**
 * 按 NDJSON 行帧（每行一个 JSON 文本）读写的传输基类。
 *
 * <p>stdio 子进程与任何「行分隔帧」通道都可复用本类：写端把请求/通知序列化为一行写出去，
 * 读端后台线程逐行解析，把带 id 的响应帧按 id 投递回对应的等待者。服务端主动发来的
 * request/notification（有 method 无 id 配对）在本客户端内暂不处理，直接丢弃。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public abstract class LineFrameMcpTransport implements McpTransport {

	/** 等待响应的默认超时。 */
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

	private final InputStream in;
	private final OutputStream out;
	private final Duration timeout;
	private final ConcurrentHashMap<Long, CompletableFuture<McpResponse>> pending = new ConcurrentHashMap<>();
	private final Thread reader;
	private volatile boolean open;

	/**
	 * 全参构造（并启动读线程）。
	 *
	 * @param in      对端输出流（读到的 JSON-RPC 帧）
	 * @param out     对端输入流（写入的 JSON-RPC 帧）
	 * @param timeout 单次请求等待响应超时
	 */
	protected LineFrameMcpTransport(InputStream in, OutputStream out, Duration timeout) {
		this.in = in;
		this.out = out;
		this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
		this.open = true;
		this.reader = new Thread(this::readLoop, "sureai-mcp-stdio-reader");
		this.reader.setDaemon(true);
		this.reader.start();
	}

	/**
	 * 实际关闭底层资源（子进程 destroy / 流关闭），由 {@link #close()} 调用。
	 */
	protected abstract void doClose();

	@Override
	public McpResponse sendRequest(McpRequest request) {
		ensureOpen();
		CompletableFuture<McpResponse> future = new CompletableFuture<>();
		this.pending.put(request.id(), future);
		try {
			writeLine(Json.stringify(request.toJson()));
			return future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.TimeoutException ex) {
			this.pending.remove(request.id());
			throw new AiException("MCP 响应超时（" + this.timeout + "）: " + request.method(), ex);
		} catch (Exception ex) {
			this.pending.remove(request.id());
			throw new AiException("MCP 请求发送失败: " + request.method(), ex);
		}
	}

	@Override
	public void sendNotification(McpNotification notification) {
		ensureOpen();
		try {
			writeLine(Json.stringify(notification.toJson()));
		} catch (IOException ex) {
			throw new AiException("MCP 通知发送失败: " + notification.method(), ex);
		}
	}

	/** 写一行（末尾补 \n）并 flush。 */
	private synchronized void writeLine(String line) throws IOException {
		byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
		this.out.write(bytes);
		this.out.flush();
	}

	/** 读循环：逐行解析 JSON-RPC 帧并分发。 */
	private void readLoop() {
		BufferedReader br = new BufferedReader(new InputStreamReader(this.in, StandardCharsets.UTF_8));
		try {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				dispatch(line);
			}
		} catch (IOException ex) {
			if (this.open) {
				failPending(new AiException("MCP 传输读取失败: " + ex.getMessage(), ex));
			}
		} finally {
			this.open = false;
			failPending(new AiException("MCP 传输已关闭"));
		}
	}

	/** 把一行 JSON 分发为响应帧或丢弃。 */
	private void dispatch(String line) {
		try {
			JsonElement el = Json.parse(line);
			if (!el.isObject()) {
				return;
			}
			JsonObject obj = el.getAsJsonObject();
			boolean hasId = obj.has("id");
			boolean hasResultOrError = obj.has("result") || obj.has("error");
			if (hasId && hasResultOrError) {
				McpResponse resp = McpResponse.fromJson(obj);
				if (resp.id() != null) {
					CompletableFuture<McpResponse> f = this.pending.remove(resp.id());
					if (f != null) {
						f.complete(resp);
					}
				}
			}
			// 其余为服务端主动 request/notification，本客户端暂不处理
		} catch (RuntimeException ex) {
			// 单行解析失败不中断读循环
		}
	}

	/** 所有等待者异常完成。 */
	private void failPending(AiException ex) {
		this.pending.forEach((id, f) -> f.completeExceptionally(ex));
		this.pending.clear();
	}

	private void ensureOpen() {
		if (!this.open) {
			throw new AiException("MCP 传输已关闭");
		}
	}

	@Override
	public boolean isOpen() {
		return this.open;
	}

	@Override
	public void close() {
		this.open = false;
		try {
			doClose();
		} finally {
			failPending(new AiException("MCP 传输已关闭"));
		}
	}
}
