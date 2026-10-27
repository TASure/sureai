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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
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
import com.sure.ai.mcp.server.skill.McpSkillInfo;
import com.sure.ai.mcp.server.skill.McpSkillResource;
import com.sure.ai.mcp.server.skill.McpSkillSource;

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
 *   <li><b>无状态 2026-07-28（正式基线）</b>：当请求 {@code params._meta} 携带
 *       {@code io.modelcontextprotocol/protocolVersion}、或方法为 {@code server/discover} 时，
 *       直接处理请求（无需 initialize），结果带 {@code resultType="complete"}、{@code _meta.serverInfo}，
 *       可缓存结果带 {@code ttlMs}/{@code cacheScope}。</li>
 * </ul>
 *
 * <p><b>2.9.0 能力扩展</b>：</p>
 * <ul>
 *   <li>动态工具来源 {@link McpToolSource}（工具市场聚合）：{@code tools/list} 合并静态表与动态来源，
 *       输出标准 {@code title}/{@code annotations}，私有元数据放 {@code _meta}；</li>
 *   <li>Skills over MCP 扩展 {@code io.modelcontextprotocol/skills}（SEP-2640 Final）：
 *       {@link McpSkillSource} 经 {@code skills/list}、{@code skills/get} 暴露技能，
 *       正文/资源经标准 {@code resources/read}（{@code skill://} URI）读取。</li>
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

	/** 无状态规范版本号（正式基线，draft 镜像它）。 */
	public static final String PROTOCOL_VERSION_STATELESS = "2026-07-28";

	/** {@code _meta} 中声明协议版本的键。 */
	public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

	/** {@code _meta} 中声明服务端身份的键。 */
	public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

	/** Skills 官方扩展标识。 */
	public static final String EXTENSION_SKILLS = "io.modelcontextprotocol/skills";

	/** Skills 正文与资源的 URI scheme。 */
	public static final String SKILL_URI_SCHEME = "skill://";

	/** {@code skills/list} 单页条目数（规范未定大小，取 100）。 */
	public static final int DEFAULT_SKILL_PAGE_SIZE = 100;

	private final List<McpServerTool> tools = new CopyOnWriteArrayList<>();
	private final Map<String, McpServerTool> byName = new ConcurrentHashMap<>();
	private final List<McpToolSource> toolSources = new CopyOnWriteArrayList<>();
	private final List<McpSkillSource> skillSources = new CopyOnWriteArrayList<>();
	private final List<AutoCloseable> transports = new CopyOnWriteArrayList<>();

	private String serverName = "sure-ai-mcp-server";
	private String serverVersion = "1.5.0";
	private String instructions;
	private long ttlMs = 3_600_000L;
	private String cacheScope = "public";
	private int skillPageSize = DEFAULT_SKILL_PAGE_SIZE;

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
	 * 设置 {@code skills/list} 单页大小（正整数）。
	 *
	 * @param size 页大小
	 * @return this
	 */
	public McpServer skillPageSize(int size) {
		if (size <= 0) {
			throw new IllegalArgumentException("skillPageSize 必须为正整数");
		}
		this.skillPageSize = size;
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
	 * 注册动态工具来源（工具市场聚合）；{@code tools/list} 每次合并静态表与所有来源。
	 *
	 * @param source 动态工具来源
	 * @return this
	 */
	public McpServer registerToolSource(McpToolSource source) {
		if (source != null) {
			this.toolSources.add(source);
		}
		return this;
	}

	/**
	 * 注册技能来源（Skills over MCP 扩展）。
	 *
	 * @param source 技能来源
	 * @return this
	 */
	public McpServer registerSkillSource(McpSkillSource source) {
		if (source != null) {
			this.skillSources.add(source);
		}
		return this;
	}

	/**
	 * 当前已注册静态工具的不可变快照（不含动态来源）。
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
			// 2026-07-28 MRTR：所有结果都必须带 resultType（核心枚举 complete/input_required）。
			if (!result.has("resultType")) {
				result.put("resultType", "complete");
			}
			// 无状态模式下，服务端应在每个结果的 _meta 里自报身份（已有 _meta 不覆盖）。
			if (stateless && !result.has("_meta")) {
				result.set("_meta", metaWithServerInfo());
			}
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
		// 帧级协议版本校验：HTTP 层已校验 MCP-Protocol-Version 头，此处覆盖 stdio 等
		// 不经 HTTP 头的传输；声明了无法识别的版本即 -32022（data 含 supported/requested）。
		checkProtocolVersion(params);
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
			case "skills/list":
				return skillsListResult(params, stateless);
			case "skills/get":
				return skillsGetResult(params, stateless);
			case "resources/list":
				return listResult("resources", Json.array(), stateless);
			case "resources/read":
				return resourcesReadResult(params, stateless);
			case "resources/directory/read":
				return resourcesDirectoryReadResult(params, stateless);
			case "prompts/list":
				return listResult("prompts", Json.array(), stateless);
			case "subscriptions/listen":
				return subscriptionsListenResult(params);
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

	/**
	 * 校验帧 _meta 中声明的协议版本；未声明或声明受支持版本放行，否则抛
	 * -32022 UnsupportedProtocolVersion。
	 *
	 * @param params 请求参数
	 */
	private void checkProtocolVersion(JsonObject params) {
		if (params == null || !params.has("_meta") || !params.get("_meta").isObject()) {
			return;
		}
		JsonObject meta = params.get("_meta").getAsJsonObject();
		String version = meta.optString(META_PROTOCOL_VERSION, null);
		if (version == null || PROTOCOL_VERSION_STATEFUL.equals(version)
			|| PROTOCOL_VERSION_STATELESS.equals(version)) {
			return;
		}
		JsonObject data = Json.object();
		data.put("requested", version);
		JsonArray supported = Json.array();
		supported.add(PROTOCOL_VERSION_STATEFUL);
		supported.add(PROTOCOL_VERSION_STATELESS);
		data.set("supported", supported);
		throw new McpRpcException(McpError.UNSUPPORTED_PROTOCOL_VERSION,
			"Unsupported protocol version: " + version, data);
	}

	/** 有状态 initialize 握手结果。 */
	private JsonObject initializeResult() {
		JsonObject result = Json.object();
		result.put("protocolVersion", PROTOCOL_VERSION_STATEFUL);
		JsonObject caps = Json.object();
		caps.set("tools", Json.object());
		caps.set("resources", Json.object());
		caps.set("prompts", Json.object());
		addSkillsCapability(caps);
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
		caps.set("prompts", Json.object());
		// 注册了技能来源时，resources 能力与 Skills 扩展才成立
		if (!this.skillSources.isEmpty()) {
			caps.set("resources", Json.object());
			addSkillsCapability(caps);
		}
		result.set("capabilities", caps);
		if (this.instructions != null) {
			result.put("instructions", this.instructions);
		}
		result.put("ttlMs", this.ttlMs);
		result.put("cacheScope", this.cacheScope);
		result.set("_meta", metaWithServerInfo());
		return result;
	}

	/** 若注册了技能来源，在 capabilities 中声明官方 Skills 扩展。 */
	private void addSkillsCapability(JsonObject caps) {
		if (this.skillSources.isEmpty()) {
			return;
		}
		JsonObject extensions = Json.object();
		JsonObject skills = Json.object();
		skills.put("directoryRead", anyDirectoryRead());
		extensions.set(EXTENSION_SKILLS, skills);
		caps.set("extensions", extensions);
	}

	/** 是否有任一技能来源支持目录读取。 */
	private boolean anyDirectoryRead() {
		for (McpSkillSource s : this.skillSources) {
			if (s.directoryRead()) {
				return true;
			}
		}
		return false;
	}

	/** 合并静态表与全部动态来源的工具（按名去重，静态优先，来源间先注册优先）。 */
	private List<McpServerTool> mergedTools() {
		Map<String, McpServerTool> merged = new LinkedHashMap<>();
		for (McpServerTool t : this.tools) {
			merged.putIfAbsent(t.name(), t);
		}
		for (McpToolSource source : this.toolSources) {
			List<McpServerTool> list = source.tools();
			if (list == null) {
				continue;
			}
			for (McpServerTool t : list) {
				if (t != null) {
					merged.putIfAbsent(t.name(), t);
				}
			}
		}
		return new ArrayList<>(merged.values());
	}

	/** tools/list 结果。 */
	private JsonObject toolsListResult(boolean stateless) {
		JsonArray arr = Json.array();
		for (McpServerTool t : mergedTools()) {
			arr.add(toolObject(t));
		}
		return listResult("tools", arr, stateless);
	}

	/** 序列化单个工具对象（含标准 title/annotations 与私有 _meta）。 */
	private static JsonObject toolObject(McpServerTool t) {
		JsonObject o = Json.object();
		o.put("name", t.name());
		String title = t.title();
		if (title == null && t.annotations() != null) {
			title = t.annotations().title();
		}
		if (title != null) {
			o.put("title", title);
		}
		if (t.description() != null) {
			o.put("description", t.description());
		}
		o.set("inputSchema", t.inputSchema());
		JsonObject annotations = annotationsObject(t.annotations());
		if (annotations != null) {
			o.set("annotations", annotations);
		}
		if (t.meta() != null) {
			o.set("_meta", t.meta());
		}
		return o;
	}

	/** 只输出非空 hint 的 annotations 对象；全空时返回 null。 */
	private static JsonObject annotationsObject(McpToolAnnotations a) {
		if (a == null) {
			return null;
		}
		JsonObject o = Json.object();
		boolean any = false;
		if (a.readOnlyHint() != null) {
			o.put("readOnlyHint", a.readOnlyHint());
			any = true;
		}
		if (a.destructiveHint() != null) {
			o.put("destructiveHint", a.destructiveHint());
			any = true;
		}
		if (a.idempotentHint() != null) {
			o.put("idempotentHint", a.idempotentHint());
			any = true;
		}
		if (a.openWorldHint() != null) {
			o.put("openWorldHint", a.openWorldHint());
			any = true;
		}
		return any ? o : null;
	}

	/** 组装 list 类结果，无状态时注入缓存提示与 resultType/_meta。 */
	private JsonObject listResult(String key, JsonArray items, boolean stateless) {
		JsonObject result = Json.object();
		result.set(key, items);
		if (stateless) {
			applyCacheHints(result);
		}
		return result;
	}

	/** 无状态可缓存结果统一注入缓存提示。 */
	private void applyCacheHints(JsonObject result) {
		result.put("resultType", "complete");
		result.put("ttlMs", this.ttlMs);
		result.put("cacheScope", this.cacheScope);
		result.set("_meta", metaWithServerInfo());
	}

	/**
	 * {@code subscriptions/listen} 结果：回显服务端承诺的订阅子集。
	 *
	 * <p>HTTP 传输层会把该请求升级为 SSE 长连接并先回 ack 通知（见
	 * {@link HttpMcpServerTransport}）；同步传输（stdio）下则以本结果即时应答。</p>
	 */
	private JsonObject subscriptionsListenResult(JsonObject params) {
		JsonObject result = Json.object();
		result.set("notifications", McpSubscriptions.accepted(params));
		return result;
	}

	/** tools/call：查工具、执行、包装 content。 */
	private JsonObject toolsCall(JsonObject params) {
		String name = params == null ? null : params.optString("name", null);
		if (name == null) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Missing tool name", null);
		}
		JsonObject arguments = params != null && params.has("arguments") && params.get("arguments").isObject()
			? params.get("arguments").getAsJsonObject() : Json.object();
		McpServerTool tool = resolveTool(name);
		if (tool == null) {
			// 2026-07-28：未知工具属于 InvalidParams（-32602），不是工具执行结果
			throw new McpRpcException(McpError.INVALID_PARAMS, "Unknown tool: " + name, null);
		}
		McpToolResult tr;
		try {
			tr = tool.apply(arguments);
		} catch (RuntimeException ex) {
			// 工具自身执行失败：isError=true 的工具结果（协议标准行为）
			tr = new McpToolResult(true, List.of("Tool '" + name + "' failed: " + ex.getMessage()));
		}
		JsonObject result = Json.object();
		JsonArray content = Json.array();
		for (String text : tr.textContents()) {
			JsonObject item = Json.object();
			item.put("type", "text");
			item.put("text", text);
			content.add(item);
		}
		result.set("content", content);
		result.put("isError", tr.isError());
		return result;
	}

	/** 静态表优先，其次遍历动态来源解析工具。 */
	private McpServerTool resolveTool(String name) {
		McpServerTool direct = this.byName.get(name);
		if (direct != null) {
			return direct;
		}
		for (McpToolSource source : this.toolSources) {
			List<McpServerTool> list = source.tools();
			if (list == null) {
				continue;
			}
			for (McpServerTool t : list) {
				if (t != null && name.equals(t.name())) {
					return t;
				}
			}
		}
		return null;
	}

	/** 聚合全部技能来源的技能清单（按 URI 去重，先注册优先）。 */
	private List<McpSkillInfo> mergedSkills() {
		Map<String, McpSkillInfo> merged = new LinkedHashMap<>();
		for (McpSkillSource source : this.skillSources) {
			List<McpSkillInfo> list = source.listSkills();
			if (list == null) {
				continue;
			}
			for (McpSkillInfo info : list) {
				if (info != null) {
					merged.putIfAbsent(info.uri(), info);
				}
			}
		}
		return new ArrayList<>(merged.values());
	}

	/** skills/list：分页返回技能清单。 */
	private JsonObject skillsListResult(JsonObject params, boolean stateless) {
		if (this.skillSources.isEmpty()) {
			throw new McpRpcException(McpError.METHOD_NOT_FOUND,
				"Server does not provide extension: " + EXTENSION_SKILLS, null);
		}
		List<McpSkillInfo> all = mergedSkills();
		int offset = decodeCursor(params == null ? null : params.optString("cursor", null));
		if (offset > all.size()) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Invalid cursor", null);
		}
		int end = Math.min(offset + this.skillPageSize, all.size());
		JsonArray arr = Json.array();
		for (McpSkillInfo info : all.subList(offset, end)) {
			arr.add(skillObject(info));
		}
		JsonObject result = Json.object();
		result.set("skills", arr);
		if (end < all.size()) {
			result.put("nextCursor", encodeCursor(end));
		}
		applyCacheHints(result);
		if (!stateless) {
			// 有状态请求下也要保留 resultType（dispatch 兜底），但不强制缓存提示
			result.remove("ttlMs");
			result.remove("cacheScope");
			result.remove("_meta");
		}
		return result;
	}

	/** skills/get：按 URI 返回单个技能。 */
	private JsonObject skillsGetResult(JsonObject params, boolean stateless) {
		if (this.skillSources.isEmpty()) {
			throw new McpRpcException(McpError.METHOD_NOT_FOUND,
				"Server does not provide extension: " + EXTENSION_SKILLS, null);
		}
		String uri = params == null ? null : params.optString("uri", null);
		if (uri == null || uri.isBlank()) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Missing skill uri", null);
		}
		McpSkillInfo info = resolveSkill(uri);
		if (info == null) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Unknown skill: " + uri, null);
		}
		JsonObject result = Json.object();
		result.set("skill", skillObject(info));
		applyCacheHints(result);
		if (!stateless) {
			result.remove("ttlMs");
			result.remove("cacheScope");
			result.remove("_meta");
		}
		return result;
	}

	/** 按 URI 在各来源查找技能。 */
	private McpSkillInfo resolveSkill(String uri) {
		for (McpSkillSource source : this.skillSources) {
			McpSkillInfo info = source.getSkill(uri);
			if (info != null) {
				return info;
			}
		}
		return null;
	}

	/** resources/read：当前仅支持 skill:// 方案的技能正文/资源。 */
	private JsonObject resourcesReadResult(JsonObject params, boolean stateless) {
		String uri = params == null ? null : params.optString("uri", null);
		if (uri == null || uri.isBlank()) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Missing resource uri", null);
		}
		if (!uri.startsWith(SKILL_URI_SCHEME)) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Unknown resource: " + uri, null);
		}
		String text = null;
		for (McpSkillSource source : this.skillSources) {
			text = source.readResource(uri);
			if (text != null) {
				break;
			}
		}
		if (text == null) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Unknown resource: " + uri, null);
		}
		JsonObject item = Json.object();
		item.put("uri", uri);
		item.put("mimeType", uri.endsWith(".md") ? "text/markdown" : "text/plain");
		item.put("text", text);
		JsonArray contents = Json.array();
		contents.add(item);
		JsonObject result = Json.object();
		result.set("contents", contents);
		// 规范：resources/read 属于 CacheableResult
		if (stateless) {
			applyCacheHints(result);
		}
		return result;
	}

	/** resources/directory/read：扩展可选能力，仅 directoryRead 来源支持。 */
	private JsonObject resourcesDirectoryReadResult(JsonObject params, boolean stateless) {
		String uri = params == null ? null : params.optString("uri", null);
		if (uri == null || uri.isBlank()) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Missing directory uri", null);
		}
		List<McpSkillResource> entries = null;
		for (McpSkillSource source : this.skillSources) {
			if (!source.directoryRead()) {
				continue;
			}
			try {
				List<McpSkillResource> found = source.listDirectory(uri);
				if (found != null) {
					entries = found;
					break;
				}
			} catch (IllegalArgumentException ex) {
				throw new McpRpcException(McpError.INVALID_PARAMS, ex.getMessage(), null);
			}
		}
		if (entries == null) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Unknown directory: " + uri, null);
		}
		JsonArray arr = Json.array();
		for (McpSkillResource r : entries) {
			JsonObject o = Json.object();
			o.put("uri", r.uri());
			if (r.digest() != null) {
				o.put("digest", r.digest());
			}
			o.put("size", r.size());
			arr.add(o);
		}
		JsonObject result = Json.object();
		result.set("contents", arr);
		if (stateless) {
			applyCacheHints(result);
		}
		return result;
	}

	/** 序列化技能对象（resources 为静态列表或 "dynamic"）。 */
	private static JsonObject skillObject(McpSkillInfo info) {
		JsonObject o = Json.object();
		o.put("uri", info.uri());
		JsonObject fm = info.frontmatter() == null ? Json.object() : info.frontmatter();
		o.set("frontmatter", fm);
		if (info.dynamic()) {
			o.put("resources", "dynamic");
		} else {
			JsonArray res = Json.array();
			for (McpSkillResource r : info.resources()) {
				JsonObject ro = Json.object();
				ro.put("uri", r.uri());
				if (r.digest() != null) {
					ro.put("digest", r.digest());
				}
				ro.put("size", r.size());
				res.add(ro);
			}
			o.set("resources", res);
		}
		return o;
	}

	/** 编码分页游标（不透明 base64-url，内容为偏移量）。 */
	private static String encodeCursor(int offset) {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
	}

	/** 解码分页游标；空游标为 0；非法抛 -32602。 */
	private static int decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return 0;
		}
		try {
			int offset = Integer.parseInt(new String(
				Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
			if (offset < 0) {
				throw new IllegalArgumentException();
			}
			return offset;
		} catch (RuntimeException ex) {
			throw new McpRpcException(McpError.INVALID_PARAMS, "Invalid cursor", null);
		}
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
