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

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.internal.json.JsonPrimitive;

/**
 * {@code subscriptions/listen}（2026-07-28 无状态规范）订阅过滤器与确认帧的共享组装逻辑。
 *
 * <p>规范依据：<a href="https://modelcontextprotocol.io/specification/draft/basic/patterns/subscriptions">
 * Subscriptions 模式</a>。{@code subscriptions/listen} 取代旧的 HTTP GET 端点与
 * {@code resources/subscribe}/{@code resources/unsubscribe}：客户端在 {@code params.notifications}
 * 里声明想接收的事件类型，服务端<b>必须不</b>发送未声明的类型，并以
 * {@code notifications/subscriptions/acknowledged} 作为流上第一条消息，在 {@code _meta} 里携带
 * {@code io.modelcontextprotocol/subscriptionId}（值即本订阅请求的 JSON-RPC id）。</p>
 *
 * <p>本类只做纯数据组装，不触碰 I/O，供 {@link McpServer}（同步 ack 结果）与
 * {@link HttpMcpServerTransport}（SSE 流长连接）共用同一套过滤语义。</p>
 *
 * @author sureai
 * @since 2.3.0
 */
public final class McpSubscriptions {

	/** 订阅键：工具列表变化（{@code notifications/tools/list_changed}）。 */
	public static final String TOOLS_LIST_CHANGED = "toolsListChanged";

	/** 订阅键：提示模板列表变化（{@code notifications/prompts/list_changed}）。 */
	public static final String PROMPTS_LIST_CHANGED = "promptsListChanged";

	/** 订阅键：资源列表变化（{@code notifications/resources/list_changed}）。 */
	public static final String RESOURCES_LIST_CHANGED = "resourcesListChanged";

	/** 订阅键：指定资源 URI 更新（{@code notifications/resources/updated}）。 */
	public static final String RESOURCE_SUBSCRIPTIONS = "resourceSubscriptions";

	/** {@code _meta} 中携带订阅 id 的键。 */
	public static final String META_SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId";

	/** 确认通知方法名。 */
	public static final String ACK_METHOD = "notifications/subscriptions/acknowledged";

	private McpSubscriptions() {
		throw new AssertionError("No instances");
	}

	/**
	 * 计算服务端实际承诺的订阅子集：只保留本服务端理解且客户端显式声明的键。
	 *
	 * <p>布尔型键仅在 {@code true} 时保留；{@code resourceSubscriptions} 仅在为字符串数组时透传。
	 * 服务端不支持/不认识的键一律丢弃（规范要求 ack 只回显承诺子集）。</p>
	 *
	 * @param params {@code subscriptions/listen} 请求的 params（可空）
	 * @return 承诺的 {@code notifications} 对象（可能为空）
	 */
	public static JsonObject accepted(JsonObject params) {
		JsonObject out = Json.object();
		if (params == null || !params.has("notifications") || !params.get("notifications").isObject()) {
			return out;
		}
		JsonObject req = params.get("notifications").getAsJsonObject();
		copyBool(req, out, TOOLS_LIST_CHANGED);
		copyBool(req, out, PROMPTS_LIST_CHANGED);
		copyBool(req, out, RESOURCES_LIST_CHANGED);
		if (req.has(RESOURCE_SUBSCRIPTIONS) && req.get(RESOURCE_SUBSCRIPTIONS).isArray()) {
			out.set(RESOURCE_SUBSCRIPTIONS, req.get(RESOURCE_SUBSCRIPTIONS));
		}
		return out;
	}

	/** 拷贝单个布尔订阅键（仅当为 true）。 */
	private static void copyBool(JsonObject req, JsonObject out, String key) {
		if (req.has(key) && req.get(key).isBoolean() && req.get(key).getAsBoolean()) {
			out.put(key, Boolean.TRUE);
		}
	}

	/**
	 * 组装 {@code notifications/subscriptions/acknowledged} 通知帧。
	 *
	 * @param id       订阅请求的 JSON-RPC id（作为 subscriptionId 回显）
	 * @param accepted {@link #accepted(JsonObject)} 算出的承诺子集
	 * @return 完整通知对象
	 */
	public static JsonObject ackNotification(JsonElement id, JsonObject accepted) {
		JsonObject params = Json.object();
		JsonObject meta = Json.object();
		meta.set(META_SUBSCRIPTION_ID, id == null ? JsonPrimitive.jsonNull() : id);
		params.set("_meta", meta);
		params.set("notifications", accepted == null ? Json.object() : accepted);
		JsonObject n = Json.object();
		n.put("jsonrpc", "2.0");
		n.put("method", ACK_METHOD);
		n.set("params", params);
		return n;
	}
}
