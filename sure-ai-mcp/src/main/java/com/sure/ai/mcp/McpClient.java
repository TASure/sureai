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

package com.sure.ai.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;
import com.sure.ai.mcp.model.McpPrompt;
import com.sure.ai.mcp.model.McpPromptResult;
import com.sure.ai.mcp.model.McpResource;
import com.sure.ai.mcp.model.McpResourceContent;
import com.sure.ai.mcp.model.McpTool;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.mcp.transport.McpTransport;
import com.sure.ai.mcp.transport.StdioMcpTransport;
import com.sure.ai.mcp.transport.StreamableHttpMcpTransport;
import com.sure.tool.lang.Assert;

/**
 * MCP 客户端：封装 initialize 握手与 tools/resources/prompts 能力调用。
 *
 * <p>用法：</p>
 * <pre>{@code
 * try (McpClient client = McpClient.stdio("node", "server.js").build()) {
 *     List<McpTool> tools = client.toolsList();
 *     McpToolResult r = client.toolsCall("add", Json.object().set("a", 1).set("b", 2));
 * }
 * }</pre>
 *
 * <p>实现 {@link AutoCloseable}，try-with-resources 自动发 {@code notifications/closed} 并关闭传输。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class McpClient implements AutoCloseable {

	/** 默认协议版本。 */
	public static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

	private final McpTransport transport;
	private final String protocolVersion;
	private final AtomicLong idCounter = new AtomicLong(0);
	private volatile boolean closed;

	/**
	 * 全参构造（并执行 initialize 握手）。
	 *
	 * @param transport       已就绪传输
	 * @param protocolVersion 协议版本
	 */
	McpClient(McpTransport transport, String protocolVersion) {
		this.transport = transport;
		this.protocolVersion = protocolVersion;
		handshake();
	}

	/** 执行 initialize 握手。 */
	private void handshake() {
		JsonObject clientCaps = Json.object();
		clientCaps.set("roots", Json.object());
		JsonObject clientInfo = Json.object();
		clientInfo.put("name", "sure-ai-mcp");
		clientInfo.put("version", "1.2.0");
		JsonObject params = Json.object();
		params.put("protocolVersion", this.protocolVersion);
		params.put("capabilities", clientCaps);
		params.put("clientInfo", clientInfo);
		call("initialize", params);
		this.transport.sendNotification(new McpNotification("notifications/initialized"));
	}

	/**
	 * 通用方法调用。
	 *
	 * @param method 方法名
	 * @param params 参数（可空）
	 * @return result 元素
	 */
	public JsonElement call(String method, JsonObject params) {
		ensureOpen();
		long id = this.idCounter.incrementAndGet();
		McpResponse resp = this.transport.sendRequest(new McpRequest(id, method, params));
		if (resp.isError()) {
			throw new AiException("MCP 错误 " + resp.error().code() + ": " + resp.error().message());
		}
		return resp.result();
	}

	/**
	 * 列出工具。
	 *
	 * @return 工具列表
	 */
	public List<McpTool> toolsList() {
		JsonElement result = call("tools/list", null);
		return parseList(result, "tools", McpTool::fromJson);
	}

	/**
	 * 调用工具。
	 *
	 * @param name      工具名
	 * @param arguments 入参对象
	 * @return 工具结果
	 */
	public McpToolResult toolsCall(String name, JsonObject arguments) {
		JsonObject params = Json.object();
		params.put("name", name);
		params.put("arguments", arguments == null ? Json.object() : arguments);
		JsonElement result = call("tools/call", params);
		return McpToolResult.fromElement(result);
	}

	/**
	 * 列出资源。
	 *
	 * @return 资源列表
	 */
	public List<McpResource> resourcesList() {
		JsonElement result = call("resources/list", null);
		return parseList(result, "resources", McpResource::fromJson);
	}

	/**
	 * 读取资源。
	 *
	 * @param uri 资源 URI
	 * @return 资源内容列表
	 */
	public List<McpResourceContent> resourcesRead(String uri) {
		JsonObject params = Json.object();
		params.put("uri", uri);
		return McpResourceContent.fromElement(call("resources/read", params));
	}

	/**
	 * 列出提示模板。
	 *
	 * @return 提示列表
	 */
	public List<McpPrompt> promptsList() {
		JsonElement result = call("prompts/list", null);
		return parseList(result, "prompts", McpPrompt::fromJson);
	}

	/**
	 * 渲染提示模板。
	 *
	 * @param name      提示名
	 * @param arguments 模板参数（可空）
	 * @return 提示结果
	 */
	public McpPromptResult promptsGet(String name, Map<String, Object> arguments) {
		JsonObject params = Json.object();
		params.put("name", name);
		if (arguments != null && !arguments.isEmpty()) {
			JsonObject args = Json.object();
			arguments.forEach(args::set);
			params.put("arguments", args);
		}
		return McpPromptResult.fromElement(call("prompts/get", params));
	}

	/** 解析 result.<arrayKey> 为对象列表。 */
	private static <T> List<T> parseList(JsonElement result, String arrayKey,
			java.util.function.Function<JsonObject, T> mapper) {
		List<T> out = new ArrayList<>();
		if (result != null && result.isObject() && result.getAsJsonObject().has(arrayKey)) {
			JsonArray arr = result.getAsJsonObject().getJsonArray(arrayKey);
			for (int i = 0; i < arr.size(); i++) {
				out.add(mapper.apply(arr.get(i).getAsJsonObject()));
			}
		}
		return out;
	}

	private void ensureOpen() {
		if (this.closed || !this.transport.isOpen()) {
			throw new AiException("MCP 客户端已关闭");
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		try {
			this.transport.sendNotification(new McpNotification("notifications/closed"));
		} catch (RuntimeException ex) {
			// 关闭通知失败不影响 transport 关闭
		}
		this.transport.close();
	}

	/**
	 * 客户端是否已关闭。
	 *
	 * @return 已关闭返回 true
	 */
	public boolean isClosed() {
		return this.closed;
	}

	/**
	 * 构造 stdio 客户端 Builder。
	 *
	 * @param command 可执行命令
	 * @return Builder
	 */
	public static Builder stdio(String command) {
		return new Builder().stdioCommand(command);
	}

	/**
	 * 构造 HTTP 客户端 Builder。
	 *
	 * @param url 单端点 URL
	 * @return Builder
	 */
	public static Builder http(String url) {
		return new Builder().httpUrl(url);
	}

	/**
	 * 客户端 Builder。
	 */
	public static final class Builder {

		private String stdioCommand;
		private final List<String> stdioArgs = new ArrayList<>();
		private String httpUrl;
		private Duration timeout;
		private String protocolVersion = DEFAULT_PROTOCOL_VERSION;
		private McpTransport transport;

		/** 私有构造器。 */
		private Builder() {
		}

		/** stdio 命令。 */
		Builder stdioCommand(String command) {
			this.stdioCommand = command;
			return this;
		}

		/** HTTP url。 */
		Builder httpUrl(String url) {
			this.httpUrl = url;
			return this;
		}

		/**
		 * 追加 stdio 参数。
		 *
		 * @param arg 参数
		 * @return this
		 */
		public Builder arg(String arg) {
			this.stdioArgs.add(arg);
			return this;
		}

		/**
		 * 追加多个 stdio 参数。
		 *
		 * @param args 参数
		 * @return this
		 */
		public Builder args(List<String> args) {
			if (args != null) {
				this.stdioArgs.addAll(args);
			}
			return this;
		}

		/**
		 * 设置协议版本。
		 *
		 * @param version 协议版本
		 * @return this
		 */
		public Builder protocolVersion(String version) {
			this.protocolVersion = version;
			return this;
		}

		/**
		 * 设置请求超时。
		 *
		 * @param timeout 超时
		 * @return this
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * 注入自定义传输（测试用，覆盖 stdio/http）。
		 *
		 * @param transport 传输
		 * @return this
		 */
		public Builder transport(McpTransport transport) {
			this.transport = transport;
			return this;
		}

		/**
		 * 构建客户端并完成握手。
		 *
		 * @return 客户端
		 */
		public McpClient build() {
			McpTransport t = this.transport;
			if (t == null) {
				if (this.stdioCommand != null) {
					StdioMcpTransport.Builder sb = StdioMcpTransport.builder()
						.command(this.stdioCommand)
						.args(this.stdioArgs);
					if (this.timeout != null) {
						sb.timeout(this.timeout);
					}
					t = sb.build();
				} else if (this.httpUrl != null) {
					t = new StreamableHttpMcpTransport(this.httpUrl, this.timeout);
				} else {
					throw new AiException("必须指定 stdio(command) 或 http(url)");
				}
			}
			Assert.notNull(t, "transport");
			return new McpClient(t, this.protocolVersion);
		}
	}
}
