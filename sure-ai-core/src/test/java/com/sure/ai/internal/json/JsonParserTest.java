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

package com.sure.ai.internal.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link JsonParser} 单元测试。
 *
 * @author sureai
 * @since 0.1.0
 */
public class JsonParserTest {

	/** Unicode 转义解析。 */
	@Test
	public void testUnicodeEscape() {
		JsonElement el = Json.parse("\"\\u4e2d\\u6587\"");
		assertEquals("中文", el.getAsString());
	}

	/** 控制字符与常见转义。 */
	@Test
	public void testEscapes() {
		JsonElement el = Json.parse("\"a\\nb\\tc\\\\d\\\"e\"");
		assertEquals("a\nb\tc\\d\"e", el.getAsString());
	}

	/** 嵌套对象与数组。 */
	@Test
	public void testNested() {
		JsonObject o = (JsonObject) Json.parse("{\"a\":1,\"b\":[true,{\"c\":\"x\"}]}");
		assertEquals(1, o.getInt("a"));
		JsonArray b = o.getJsonArray("b");
		assertTrue(b.getBoolean(0));
		assertEquals("x", b.getJsonObject(1).getString("c"));
	}

	/** 超过 long 范围的大数。 */
	@Test
	public void testBigNumber() {
		JsonElement el = Json.parse("99999999999999999999999");
		assertTrue(el.isNumber());
		assertEquals(Double.parseDouble("99999999999999999999999"), el.getAsDouble(), 0);
	}

	/** 科学计数法。 */
	@Test
	public void testScientific() {
		JsonElement el = Json.parse("1.5e3");
		assertEquals(1500.0, el.getAsDouble(), 0);
	}

	/** null 与布尔。 */
	@Test
	public void testNullBool() {
		assertTrue(Json.parse("null").isNull());
		assertTrue(Json.parse("true").getAsBoolean());
		assertFalse(Json.parse("false").getAsBoolean());
	}

	/** 尾随空白。 */
	@Test
	public void testTrailingWhitespace() {
		JsonElement el = Json.parse("  42  \n ");
		assertEquals(42, el.getAsInt());
	}

	/** 语法错误抛异常。 */
	@Test
	public void testSyntaxError() {
		try {
			Json.parse("{bad json");
			fail("should throw");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().startsWith("Invalid JSON"));
		}
	}

	/** 非法字面量。 */
	@Test
	public void testInvalidLiteral() {
		try {
			Json.parse("tru");
			fail("should throw");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("Invalid JSON"));
		}
	}

	/** stringify 往返。 */
	@Test
	public void testRoundTrip() {
		String src = "{\"name\":\"中文\",\"n\":3,\"arr\":[1,2,{\"k\":null}]}";
		JsonElement el = Json.parse(src);
		String out = Json.stringify(el);
		assertEquals(src, out);
	}

	/** optString/optInt 默认值。 */
	@Test
	public void testOpt() {
		JsonObject o = (JsonObject) Json.parse("{\"a\":\"x\",\"b\":5}");
		assertEquals("x", o.optString("a", null));
		assertEquals("def", o.optString("missing", "def"));
		assertEquals(5, o.optInt("b", 0));
		assertEquals(9, o.optInt("missing", 9));
		assertNull(o.optString("b"));
	}
}
