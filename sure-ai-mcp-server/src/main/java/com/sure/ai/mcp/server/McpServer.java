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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.internal.json.JsonPrimitive;
import com.sure.ai.mcp.message.McpError;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * MCP Server 协议引擎：持有注册式工具表，把单条 JSON-RPC 2.0 帧解析为结果或错误。
 *
 * <p>本类只做协议处理与工具分发，不直接触碰 I/O；传输（stdio / HTTP）调用
 * {@link #dispatch(String)} 传入原始帧、拿到要回写的响应帧（通知返回 {@code null}）。</p>
 *
 * <p><b>双协议自适应</b>：</p>
 * <ul>
 *   <li><b>有状态 2025-06-18</b>：响应 {@code initialize}（{@code protocolVersion}、{@code capabilities}、
 *       {@code serverInfo}），随后接受 {@code tools/list}、{@code tools/call} 等；</li>
 *   <li><b>无状态 2026-07-28（实验性）</b>：当请求 {@code params._meta} 携带
 *       {@code io.modelcontextprotocol/protocolVersion}、或方法为 {@code server/discover} 时，
 *       直接处理请求（无需 initialize），结果带 {@code resultType="complete"}、{@code _meta.serverInfo}，
 *       list 结果带 {@code ttlMs}/{@code cacheScope}。</li>
 * </ul>
 *
 * <p>线程安全：工具表用 {@link CopyOnWriteArrayList}，{@link #dispatch} 无共享可变状态，可被 HTTP
 * 多请求线程并发调用。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
public final class McpServer {

	/** 有状态规范版本号。 */
	public static final String PROTOCOL_VERSION_STATEFUL = "2025-06-18";

	/** 无状态规范版本号。 */
	public static final String PROTOCOL_VERSION_STATELESS = "2026-07-28";

	/** {@code _meta} 中声明协议版本的键。 */
	public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

	/** {@code _meta} 中声明服务端身份的键。 */
	public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

	private final List<McpServerTool> tools = new CopyOnWriteArrayList<>();
	private final Map<String, McpServerTool> byName = new ConcurrentHashMap<>();
	private final List<AutoCloseable> transports = new CopyOnWriteArrayList<>();

	private String serverName = "sure-ai-mcp-server";
	private String serverVersion = "1.5.0";
	private String instructions;
	private long ttlMs = 3_600_000L;
	private String cacheScope = "public";

	/**
	 * 创建空 server（未注册任何工具）。
	 */
	public McpServer() {
	}

	/**
	 * 设置服务端身份（{@code initialize}/discover 的 serverInfo）。
	 *
	 * @param name    服务端名
	 * @param version 版本
	 * @return this
	 */
	public McpServer serverInfo(String name, String version) {
		this.serverName = name;
		this.serverVersion = version;
		return this;
	}

	/**
	 * 设置自然语言使用指引（随 initialize/discover 返回，可空）。
	 *
	 * @param instructions 指引文本
	 * @return this
	 */
	public McpServer instructions(String instructions) {
		this.instructions = instructions;
		return this;
	}

	/**
	 * 设置 list 结果缓存提示（无状态模式生效）。
	 *
	 * @param ttlMs      新鲜度提示毫秒
	 * @param cacheScope 缓存作用域 {@code public}/{@code private}
	 * @return this
	 */
	public McpServer cacheHint(long ttlMs, String cacheScope) {
		this.ttlMs = ttlMs;
		this.cacheScope = cacheScope;
		return this;
	}

	/**
	 * 注册单个工具（同名覆盖）。
	 *
	 * @param tool 工具
	 * @return this
	 */
	public McpServer registerTool(McpServerTool tool) {
		if (tool == null) {
			return this;
		}
		this.byName.put(tool.name(), tool);
		this.tools.removeIf(t -> t.name().equals(tool.name()));
		this.tools.add(tool);
		return this;
	}

	/**
	 * 批量注册工具。
	 *
	 * @param more 工具集合
	 * @return this
	 */
	public McpServer registerTools(Collection<McpServerTool> more) {
		if (more != null) {
			for (McpServerTool t : more) {
				registerTool(t);
			}
		}
		return this;
	}

	/**
	 * 当前已注册工具的不可变快照。
	 *
	 * @return 工具列表
	 */
	public List<McpServerTool> tools() {
		return List.copyOf(this.tools);
	}

	/**
	 * 启动 stdio 传输并开始处理请求。
	 *
	 * @param transport stdio 传输
	 */
	public void start(StdioMcpServerTransport transport) {
		transport.start(this::dispatch);
		this.transports.add(transport);
	}

	/**
	 * 启动 HTTP 传输并开始处理请求。
	 *
	 * @param transport HTTP 传输
	 * @throws java.io.IOException 绑定端口失败
	 */
	public void start(HttpMcpServerTransport transport) throws java.io.IOException {
		transport.start(this::dispatch);
		this.transports.add(transport);
	}

	/**
	 * 关闭所有已启动的传输。
	 */
	public void close() {
		for (AutoCloseable t : this.transports) {
			try {
				t.close();
			} catch (Exception ex) {
				// 单个传输关闭失败不影响其余
			}
		}
		this.transports.clear();
	}

	/**
	 * 协议处理主入口：解析一条原始 JSON-RPC 帧，返回要回写的响应帧。
	 *
	 * @param rawLine 原始帧（一行 JSON）
	 * @return 响应帧 JSON 文本；通知/无需响应时返回 {@code null}
	 */
	public String dispatch(String rawLine) {
		JsonElement el;
		try {
			el = Json.parse(rawLine);
		} catch (RuntimeException ex) {
			return errorFrame(null, McpError.PARSE_ERROR, "Parse error", null);
		}
		if (el == null || !el.isObject()) {
			return errorFrame(null, McpError.INVALID_REQUEST, "Invalid Request", null);
		}
		JsonObject req = el.getAsJsonObject();
		JsonElement idEl = req.has("id") ? req.get("id") : null;
		boolean isNotification = idEl == null || idEl.isNull();
		String method = req.optString("method", null);
		if (method == null) {
			return isNotification ? null
				: errorFrame(null, McpError.INVALID_REQUEST, "Missing method", null);
		}
		JsonObject params = req.has("params") && req.get("params").isObject()
			? req.get("params").getAsJsonObject() : null;
		boolean stateless = isStateless(method, params);

		// 通知：不回响应
		if (isNotification) {
			return null;
		}
		try {
			JsonObject result = handle(method, params, stateless);
			return resultFrame(idEl, result);
		} catch (McpRpcException ex) {
			return errorFrame(idEl, ex.code, ex.getMessage(), ex.data);
		} catch (RuntimeException ex) {
			return errorFrame(idEl, McpError.INTERNAL_ERROR,
				"Internal error: " + ex.getMessage(), null);
		}
	}

	/** 路由具体方法到结果对象。 */
	private JsonObject handle(String method, JsonObject params, boolean stateless) {
		switch (method) {
			case "initialize":
				return initializeResult();
			case "ping":
				return Json.object();
			case "server/discover":
				return discoverResult();
			case "tools/list":
				return toolsListResult(stateless);
			case "tools/call":
				return toolsCall(params);
			case "resources/list":
				return listResult("resources", Json.array(), stateless);
			case "prompts/list":
				return listResult("prompts", Json.array(), stateless);
			default:
				throw new McpRpcException(McpError.METHOD_NOT_FOUND, "Method not found: " + method, null);
		}
	}

	/** 是否按无状态（2026-07-28）形态响应本请求。 */
	private boolean isStateless(String method, JsonObject params) {
		if ("server/discover".equals(method)) {
			return true;
		}
		if (params != null && params.has("_meta") && params.get("_meta").isObject()) {
			JsonObject meta = params.get("_meta").getAsJsonObject();
			if (meta.has(META_PROTOCOL_VERSION)) {
				return true;
			}
		}
		return false;
	}

	/** 有状态 initialize 握手结果。 */
	private JsonObject initializeResult() {
		JsonObject result = Json.object();
		result.put("protocolVersion", PROTOCOL_VERSION_STATEFUL);
		JsonObject caps = Json.object();
		caps.set("tools", Json.object());
		caps.set("resources", Json.object());
		caps.set("prompts", Json.object());
		result.set("capabilities", caps);
		result.set("serverInfo", serverInfoObject());
		if (this.instructions != null) {
			result.put("instructions", this.instructions);
		}
		return result;
	}

	/** 无状态 server/discover 结果。 */
	private JsonObject discoverResult() {
		JsonObject result = Json.object();
		result.put("resultType", "complete");
		JsonArray versions = Json.array();
		versions.add(PROTOCOL_VERSION_STATEFUL);
		versions.add(PROTOCOL_VERSION_STATELESS);
		result.set("supportedVersions", versions);
		JsonObject caps = Json.object();
		caps.set("tools", Json.object());
		result.set("capabilities", caps);
		if (this.instructions != null) {
			result.put("instructions", this.instructions);
		}
		result.put("ttlMs", this.ttlMs);
		result.put("cacheScope", this.cacheScope);
		result.set("_meta", metaWithServerInfo());
		return result;
	}

	/** tools/list 结果。 */
	private JsonObject toolsListResult(boolean stateless) {
		JsonArray arr = Json.array();
		for (McpServerTool t : this.tools) {
			JsonObject o = Json.object();
			o.put("name", t.name());
			if (t.description() != null) {
				o.put("description", t.description());
			}
			o.set("inputSchema", t.inputSchema());
			arr.add(o);
		}
		return listResult("tools", arr, stateless);
	}

	/** 组装 list 类结果，无状态时注入缓存提示与 resultType/_meta。 */
	private JsonObject listResult(String key, JsonArray items, boolean stateless) {
		JsonObject result = Json.object();
		result.set(key, items);
		if (stateless) {
			result.put("resultType", "complete");
			result.put("ttlMs", this.ttlMs);
			result.put("cacheScope", this.cacheScope);
			result.set("_meta", metaWithServerInfo());
		}
		return result;
	}

	/** tools/call：查工具、执行、包装 content。 */
	private JsonObject toolsCall(JsonObject params) {
		String name = params == null ? null : params.optString("name", null);
		JsonObject arguments = params != null && params.has("arguments") && params.get("arguments").isObject()
			? params.get("arguments").getAsJsonObject() : Json.object();
		McpServerTool tool = name == null ? null : this.byName.get(name);
		McpToolResult tr;
		if (tool == null) {
			tr = new McpToolResult(true, List.of("Error: unknown tool '" + name + "'"));
		} else {
			try {
				tr = tool.apply(arguments);
			} catch (RuntimeException ex) {
				tr = new McpToolResult(true, List.of("Tool '" + name + "' failed: " + ex.getMessage()));
			}
		}
		JsonObject result = Json.object();
		JsonArray content = Json.array();
		for (String text : tr.textContents()) {
			JsonObject item = Json.object();
			item.put("type", "text");
			item.put("text", text == null ? "" : text);
			content.add(item);
		}
		result.set("content", content);
		result.put("isError", tr.isError());
		return result;
	}

	/** serverInfo 对象。 */
	private JsonObject serverInfoObject() {
		JsonObject info = Json.object();
		info.put("name", this.serverName);
		info.put("version", this.serverVersion);
		return info;
	}

	/** _meta 内含 serverInfo 的对象。 */
	private JsonObject metaWithServerInfo() {
		JsonObject meta = Json.object();
		meta.set(META_SERVER_INFO, serverInfoObject());
		return meta;
	}

	/** 包装成功响应帧。 */
	private static String resultFrame(JsonElement idEl, JsonObject result) {
		JsonObject resp = Json.object();
		resp.put("jsonrpc", "2.0");
		resp.set("id", idEl == null ? JsonPrimitive.jsonNull() : idEl);
		resp.set("result", result);
		return Json.stringify(resp);
	}

	/** 包装错误响应帧。 */
	private static String errorFrame(JsonElement idEl, long code, String message, JsonElement data) {
		JsonObject resp = Json.object();
		resp.put("jsonrpc", "2.0");
		resp.set("id", idEl == null ? JsonPrimitive.jsonNull() : idEl);
		JsonObject err = Json.object();
		err.put("code", code);
		err.put("message", message);
		if (data != null) {
			err.set("data", data);
		}
		resp.set("error", err);
		return Json.stringify(resp);
	}

	/** 协议层错误（带 JSON-RPC 错误码）。 */
	static final class McpRpcException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final long code;
		private final transient JsonElement data;

		McpRpcException(long code, String message, JsonElement data) {
			super(message);
			this.code = code;
			this.data = data;
		}
	}
}
