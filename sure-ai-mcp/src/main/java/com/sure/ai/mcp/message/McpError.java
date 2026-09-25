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
 * JSON-RPC 2.0 错误对象。
 *
 * @param code    错误码
 * @param message 错误消息
 * @param data    附加数据（可空）
 * @author sureai
 * @since 1.2.0
 */
public record McpError(long code, String message, JsonElement data) {

	/** 解析错误码（标准 JSON-RPC：-32700）。 */
	public static final int PARSE_ERROR = -32700;

	/** 无效请求码（标准 JSON-RPC：-32600）。 */
	public static final int INVALID_REQUEST = -32600;

	/** 方法未找到码（标准 JSON-RPC：-32601）。 */
	public static final int METHOD_NOT_FOUND = -32601;

	/** 参数无效码（标准 JSON-RPC：-32602）。 */
	public static final int INVALID_PARAMS = -32602;

	/** 内部错误码（标准 JSON-RPC：-32603）。 */
	public static final int INTERNAL_ERROR = -32603;

	/**
	 * 从 JSON 对象解析。
	 *
	 * @param obj JSON 对象（{@code error} 字段内容）
	 * @return 错误对象
	 */
	public static McpError fromJson(JsonObject obj) {
		long code = obj.optLong("code", 0L);
		String message = obj.optString("message", "");
		JsonElement data = obj.has("data") ? obj.get("data") : null;
		return new McpError(code, message, data);
	}

	@Override
	public String toString() {
		return "McpError{code=" + this.code + ", message='" + this.message + "'}";
	}
}
