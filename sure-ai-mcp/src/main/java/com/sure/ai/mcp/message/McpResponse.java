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

import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * MCP JSON-RPC 2.0 响应：与 {@link McpRequest} 通过 id 配对。
 *
 * <p>成功时 {@code result} 有值、{@code error} 为 null；失败时反之。
 * 服务端主动通知（无 id 无 result/error）不属于本类范畴。</p>
 *
 * @param id     对应请求 id（通知类响应为 null）
 * @param result 成功结果（可空）
 * @param error  错误对象（可空）
 * @author sureai
 * @since 1.2.0
 */
public record McpResponse(Long id, JsonElement result, McpError error) {

	/**
	 * 是否为错误响应。
	 *
	 * @return 有 error 返回 true
	 */
	public boolean isError() {
		return this.error != null;
	}

	/**
	 * 从 JSON 对象解析响应帧。
	 *
	 * @param obj JSON 对象
	 * @return 响应对象
	 */
	public static McpResponse fromJson(JsonObject obj) {
		Long id = null;
		JsonElement idEl = obj.get("id");
		if (idEl != null && idEl.isNumber()) {
			id = idEl.getAsLong();
		}
		JsonElement result = obj.has("result") ? obj.get("result") : null;
		McpError error = null;
		if (obj.has("error")) {
			error = McpError.fromJson(obj.get("error").getAsJsonObject());
		}
		return new McpResponse(id, result, error);
	}

	@Override
	public String toString() {
		return "McpResponse{id=" + this.id + ", error=" + this.error + '}';
	}
}
