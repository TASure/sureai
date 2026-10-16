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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link McpHeaders} 编解码测试：覆盖 Mcp-Name 取值规则、Base64 哨兵编解码与私有构造器。
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpHeadersTest {

	/** tools/call 取 params.name。 */
	@Test
	public void mcpNameForToolsCall() {
		JsonObject p = Json.object();
		p.put("name", "echo");
		assertEquals("echo", McpHeaders.mcpNameFor("tools/call", p));
	}

	/** prompts/get 同样取 params.name。 */
	@Test
	public void mcpNameForPromptsGet() {
		JsonObject p = Json.object();
		p.put("name", "greet");
		assertEquals("greet", McpHeaders.mcpNameFor("prompts/get", p));
	}

	/** resources/read 取 params.uri。 */
	@Test
	public void mcpNameForResourcesRead() {
		JsonObject p = Json.object();
		p.put("uri", "file:///a.txt");
		assertEquals("file:///a.txt", McpHeaders.mcpNameFor("resources/read", p));
	}

	/** 其他方法或 params 为空时返回 null。 */
	@Test
	public void mcpNameForUnsupportedReturnsNull() {
		assertNull(McpHeaders.mcpNameFor("initialize", Json.object()));
		assertNull(McpHeaders.mcpNameFor("tools/list", null));
	}

	/** null 值原样返回。 */
	@Test
	public void encodeNullReturnsNull() {
		assertNull(McpHeaders.encode(null));
	}

	/** 纯 ASCII 可打印值原样返回。 */
	@Test
	public void encodeSafeAsciiPassthrough() {
		assertEquals("echo", McpHeaders.encode("echo"));
	}

	/** 非 ASCII 值走 Base64 哨兵，且可逆。 */
	@Test
	public void encodeNonAsciiRoundTrip() {
		String encoded = McpHeaders.encode("你好工具");
		assertTrue(encoded, encoded.startsWith(McpHeaders.BASE64_PREFIX));
		assertTrue(encoded, encoded.endsWith(McpHeaders.BASE64_SUFFIX));
		assertEquals("你好工具", McpHeaders.decode(encoded));
	}

	/** 本身像哨兵串的值会被再次编码（避免歧义）。 */
	@Test
	public void encodeExistingSentinelReEncoded() {
		String lookalike = McpHeaders.BASE64_PREFIX + "YWJj" + McpHeaders.BASE64_SUFFIX;
		String encoded = McpHeaders.encode(lookalike);
		// 再次编码后解码应还原回 lookalike
		assertEquals(lookalike, McpHeaders.decode(encoded));
	}

	/** 普通值解码原样返回。 */
	@Test
	public void decodePlainPassthrough() {
		assertEquals("plain", McpHeaders.decode("plain"));
		assertNull(McpHeaders.decode(null));
	}

	/** 哨兵内 base64 非法时原样返回（交上层按校验失败处理）。 */
	@Test
	public void decodeMalformedBase64ReturnsAsIs() {
		String bad = McpHeaders.BASE64_PREFIX + "@@@not-base64@@@" + McpHeaders.BASE64_SUFFIX;
		assertEquals(bad, McpHeaders.decode(bad));
	}

	/** 私有构造器应抛 AssertionError。 */
	@Test
	public void privateConstructorThrows() {
		try {
			var ctor = McpHeaders.class.getDeclaredConstructor();
			ctor.setAccessible(true);
			ctor.newInstance();
			assertTrue("应抛 AssertionError", false);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			assertEquals(AssertionError.class, ex.getCause().getClass());
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}
}
