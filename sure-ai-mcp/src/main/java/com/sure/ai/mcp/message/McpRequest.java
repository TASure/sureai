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
 * MCP JSON-RPC 2.0 请求：带递增 id，期待对端返回 {@link McpResponse}。
 *
 * <p>序列化形态：{@code {"jsonrpc":"2.0","id":1,"method":"...","params":{...}}}。
 * id 由 {@link java.util.concurrent.atomic.AtomicLong} 从 1 开始分配，本类仅持有不可变快照。</p>
 *
 * @param id     递增请求 id（从 1 开始）
 * @param method 方法名（如 {@code initialize}、{@code tools/call}）
 * @param params 参数对象（可空，序列化为无 params 字段）
 * @author sureai
 * @since 1.2.0
 */
public record McpRequest(long id, String method, JsonObject params) {

	/**
	 * 全参构造。
	 *
	 * @param id     递增请求 id
	 * @param method 方法名
	 * @param params 参数对象，可空
	 */
	public McpRequest {
	}

	/**
	 * 序列化为 JSON-RPC 对象。
	 *
	 * @return JSON 对象
	 */
	public JsonObject toJson() {
		JsonObject o = Json.object();
		o.put("jsonrpc", "2.0");
		o.put("id", this.id);
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
