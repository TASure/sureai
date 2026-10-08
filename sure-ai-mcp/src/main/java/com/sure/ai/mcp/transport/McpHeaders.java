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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.sure.ai.internal.json.JsonObject;

/**
 * Streamable HTTP 规范请求头（2026-07-28）的镜像与编解码工具。
 *
 * <p>规范依据：
 * <a href="https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http">
 * Streamable HTTP / Request Metadata</a>。规范要求把 JSON-RPC 正文字段镜像为 HTTP 头，让负载均衡、
 * 限流与网关无需解析 body 即可路由：</p>
 *
 * <table>
 *   <caption>标准请求头</caption>
 *   <tr><th>头</th><th>来源字段</th><th>适用</th></tr>
 *   <tr><td>{@code Mcp-Method}</td><td>{@code method}</td><td>所有请求</td></tr>
 *   <tr><td>{@code Mcp-Name}</td><td>{@code params.name} 或 {@code params.uri}</td>
 *       <td>{@code tools/call}、{@code resources/read}、{@code prompts/get}</td></tr>
 *   <tr><td>{@code MCP-Protocol-Version}</td><td>{@code _meta.io.modelcontextprotocol/protocolVersion}</td>
 *       <td>无状态请求</td></tr>
 * </table>
 *
 * <p>当头值无法安全地作为纯 ASCII（0x21-0x7E + 空格）传输时，须用 Base64 哨兵格式
 * {@code =?base64?<base64(UTF-8)>?=} 编码；服务端校验前须先解码。本类提供对称的
 * {@link #encode(String)} / {@link #decode(String)}。</p>
 *
 * @author sureai
 * @since 2.3.0
 */
public final class McpHeaders {

	/** 标准请求头：JSON-RPC 方法。 */
	public static final String HEADER_MCP_METHOD = "Mcp-Method";

	/** 标准请求头：工具/资源/提示名（params.name 或 params.uri）。 */
	public static final String HEADER_MCP_NAME = "Mcp-Name";

	/** 标准请求头：协议版本。 */
	public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

	/** Base64 哨兵前缀（小写，大小写敏感）。 */
	public static final String BASE64_PREFIX = "=?base64?";

	/** Base64 哨兵后缀。 */
	public static final String BASE64_SUFFIX = "?=";

	private McpHeaders() {
		throw new AssertionError("No instances");
	}

	/**
	 * 按规则计算某方法对应的 {@code Mcp-Name} 值。
	 *
	 * @param method JSON-RPC 方法
	 * @param params 请求 params（可空）
	 * @return 应放入 {@code Mcp-Name} 头的值；不适用该头的方法返回 {@code null}
	 */
	public static String mcpNameFor(String method, JsonObject params) {
		if (params == null) {
			return null;
		}
		if ("tools/call".equals(method) || "prompts/get".equals(method)) {
			return params.optString("name", null);
		}
		if ("resources/read".equals(method)) {
			return params.optString("uri", null);
		}
		return null;
	}

	/**
	 * 编码头值：纯 ASCII 可打印则原样；非 ASCII / 含空白或控制字符 / 本身就是哨兵串时，转 Base64 哨兵。
	 *
	 * @param value 原始值
	 * @return 安全的 HTTP 头值
	 */
	public static String encode(String value) {
		if (value == null) {
			return null;
		}
		if (looksLikeSentinel(value) || !isHeaderSafeAscii(value)) {
			return BASE64_PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + BASE64_SUFFIX;
		}
		return value;
	}

	/**
	 * 解码头值：若是 Base64 哨兵则解码回 UTF-8 原文，否则原样返回。
	 *
	 * @param value 头值
	 * @return 解码后的原文
	 */
	public static String decode(String value) {
		if (value != null && looksLikeSentinel(value)) {
			String b64 = value.substring(BASE64_PREFIX.length(), value.length() - BASE64_SUFFIX.length());
			try {
				return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException ex) {
				// 非法 base64：交给上层按头校验失败处理
				return value;
			}
		}
		return value;
	}

	/** 是否命中哨兵格式（用于二次编码与解码判断）。 */
	private static boolean looksLikeSentinel(String value) {
		return value.startsWith(BASE64_PREFIX) && value.endsWith(BASE64_SUFFIX) && value.length() > BASE64_PREFIX.length() + BASE64_SUFFIX.length();
	}

	/** 是否所有字符都在 RFC 9110 安全头值区间（0x20-0x7E，含空格）。 */
	private static boolean isHeaderSafeAscii(String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < 0x20 || c > 0x7E) {
				return false;
			}
		}
		return true;
	}
}
