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

package com.sure.ai.mcp.message;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;

/**
 * MCP JSON-RPC 2.0 通知：无 id，对端不返回响应。
 *
 * <p>序列化形态：{@code {"jsonrpc":"2.0","method":"notifications/initialized","params":{...}}}。</p>
 *
 * @param method 方法名（以 {@code notifications/} 开头）
 * @param params 参数对象（可空）
 * @author sureai
 * @since 1.2.0
 */
public record McpNotification(String method, JsonObject params) {

	/**
	 * 全参构造。
	 *
	 * @param method 方法名
	 * @param params 参数对象，可空
	 */
	public McpNotification {
	}

	/**
	 * 仅方法名的便捷构造。
	 *
	 * @param method 方法名
	 */
	public McpNotification(String method) {
		this(method, null);
	}

	/**
	 * 序列化为 JSON-RPC 对象。
	 *
	 * @return JSON 对象
	 */
	public JsonObject toJson() {
		JsonObject o = Json.object();
		o.put("jsonrpc", "2.0");
		o.put("method", this.method);
		if (this.params != null) {
			o.put("params", this.params);
		}
		return o;
	}

	@Override
	public String toString() {
		return Json.stringify(toJson());
	}
}
