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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * JSON 门面/解析/序列化/元素模型的边界与错误路径测试。
 *
 * <p>聚焦既有正向用例未覆盖的分支：私有构造器、畸形输入、转义字符、类型不匹配访问、
 * null 与默认值回退等。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public class JsonLayerEdgeTest {

	// ==================== Json 门面 ====================

	/** 私有构造器不可实例化（断言错误）。 */
	@Test
	public void testJsonPrivateCtor() throws Exception {
		Constructor<Json> ctor = Json.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("应抛出 AssertionError");
		} catch (InvocationTargetException ex) {
			assertTrue(ex.getCause() instanceof AssertionError);
		}
	}

	/** toElement(null) 产生 JSON null。 */
	@Test
	public void testToElementNull() {
		JsonElement el = Json.toElement(null);
		assertTrue(el.isNull());
		assertEquals("null", Json.stringify(el));
	}

	/** toElement 处理 List / Object[] / 未知类型回退。 */
	@Test
	public void testToElementListArrayAndFallback() {
		JsonElement list = Json.toElement(List.of(1, "a", true));
		assertEquals(3, list.getAsJsonArray().size());
		assertEquals("a", list.getAsJsonArray().getString(1));

		JsonElement arr = Json.toElement(new Object[] { 2, "b" });
		assertEquals(2, arr.getAsJsonArray().size());

		// 未知类型回退为其 toString 字符串
		JsonElement fb = Json.toElement(Map.of("k", "v"));
		assertTrue(fb.isObject());
	}

	/** stringify(Object) 对 Map 直接序列化。 */
	@Test
	public void testStringifyObjectMap() {
		String s = Json.stringify(Map.of("a", 1));
		assertTrue(s.contains("\"a\":1"));
	}

	// ==================== JsonParser 错误路径 ====================

	/** parse(null) 抛业务异常。 */
	@Test
	public void testParseNull() {
		try {
			Json.parse(null);
			fail("应抛出 AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("null"));
		}
	}

	/** 对象键后缺少冒号。 */
	@Test
	public void testObjectMissingColon() {
		assertParseFails("{\"a\" 1}");
	}

	/** 对象值后缺少逗号或右花括号。 */
	@Test
	public void testObjectMissingCommaOrBrace() {
		assertParseFails("{\"a\":1 \"b\"}");
	}

	/** 转义在输入末尾被截断。 */
	@Test
	public void testUnterminatedEscape() {
		assertParseFails("\"abc\\");
	}

	/** 各类转义字符正确还原：\\/ \b \f \r。 */
	@Test
	public void testAllEscapeChars() {
		JsonElement el = Json.parse("\"a\\/b\\bc\\fd\\re\"");
		assertEquals("a/b\bc\fd\re", el.getAsString());
	}

	/** 转义后字符串未闭合。 */
	@Test
	public void testUnterminatedAfterEscape() {
		assertParseFails("\"a\\nb");
	}

	/** Unicode 转义含大写十六进制位。 */
	@Test
	public void testUnicodeUppercaseHex() {
		// \u0041 == 'A'，其中 'A' 走大写分支
		JsonElement el = Json.parse("\"\\u0041\"");
		assertEquals("A", el.getAsString());
	}

	/** 畸形数字（连字符后无数字）。 */
	@Test
	public void testInvalidNumber() {
		assertParseFails("-");
	}

	private static void assertParseFails(String input) {
		try {
			Json.parse(input);
			fail("应解析失败: " + input);
		} catch (AiException ex) {
			// 期望
		}
	}

	// ==================== JsonWriter 转义 ====================

	/** 字符串含 \r \b \f 与控制字符（触发 Unicode 转义）。 */
	@Test
	public void testWriterEscapes() {
		String out = Json.stringify("a\rb\u0001c\fd");
		assertEquals("\"a\\rb\\u0001c\\fd\"", out);
	}

	/** JSON null 原始值序列化为 null。 */
	@Test
	public void testWriterNullPrimitive() {
		assertEquals("null", Json.stringify(JsonPrimitive.jsonNull()));
	}

	/** JsonWriter 私有构造器不可实例化。 */
	@Test
	public void testWriterPrivateCtor() throws Exception {
		Constructor<JsonWriter> ctor = JsonWriter.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("应抛出 AssertionError");
		} catch (InvocationTargetException ex) {
			assertTrue(ex.getCause() instanceof AssertionError);
		}
	}

	/** 反斜杠 b / f / r 转义显式覆盖。 */
	@Test
	public void testWriterBackspaceFormFeedReturn() {
		String s = "x\by\fz\r";
		String out = Json.stringify(s);
		assertTrue(out.contains("\\b"));
		assertTrue(out.contains("\\f"));
		assertTrue(out.contains("\\r"));
	}

	// ==================== JsonElement 类型化访问错误 ====================

	/** 在数组上调用标量访问器触发基类异常。 */
	@Test
	public void testArrayScalarAccessThrows() {
		JsonArray arr = new JsonArray();
		arr.add("x");
		assertThrowsAi(() -> arr.getAsString());
		assertThrowsAi(() -> arr.getAsInt());
		assertThrowsAi(() -> arr.getAsLong());
		assertThrowsAi(() -> arr.getAsDouble());
		assertThrowsAi(() -> arr.getAsBoolean());
	}

	/** 在原始值上调用对象/数组访问触发基类异常。 */
	@Test
	public void testPrimitiveContainerAccessThrows() {
		JsonPrimitive p = new JsonPrimitive("s");
		assertThrowsAi(() -> p.getAsJsonObject());
		assertThrowsAi(() -> p.getAsJsonArray());
	}

	/** 基类 optString/optInt/optLong 在非对象元素上回退默认值。 */
	@Test
	public void testOptDefaultsOnArray() {
		JsonArray arr = new JsonArray();
		arr.add(1);
		assertNull(arr.optString("k"));
		assertEquals(7, arr.optInt("k", 7));
		assertEquals(9L, arr.optLong("k", 9L));
	}

	// ==================== JsonPrimitive ====================

	/** null 原始值 getAsString 返回 "null"。 */
	@Test
	public void testNullPrimitiveAsString() {
		assertEquals("null", JsonPrimitive.jsonNull().getAsString());
	}

	/** 数字原始值 getAsString 保留原始词法；布尔值 toString。 */
	@Test
	public void testNumberAndBooleanAsString() {
		JsonElement n = Json.parse("1.5e3");
		assertEquals("1.5e3", n.getAsString());
		JsonPrimitive b = new JsonPrimitive(Boolean.TRUE);
		assertEquals("true", b.getAsString());
		assertEquals("true", b.toString());
	}

	/** 类型不匹配的访问抛业务异常。 */
	@Test
	public void testPrimitiveWrongTypeThrows() {
		JsonPrimitive s = new JsonPrimitive("notANumber");
		assertThrowsAi(s::getAsInt);
		assertThrowsAi(s::getAsLong);
		JsonPrimitive b = new JsonPrimitive(Boolean.FALSE);
		assertThrowsAi(b::getAsDouble);
		JsonPrimitive s2 = new JsonPrimitive("x");
		assertThrowsAi(s2::getAsBoolean);
	}

	// ==================== JsonArray / JsonObject 便捷方法 ====================

	/** JsonArray 各 add 重载与 set/getJsonArray/getDouble。 */
	@Test
	public void testJsonArrayHelpers() {
		JsonArray arr = new JsonArray();
		arr.add((Number) 1);
		arr.add((Boolean) true);
		arr.set("v");
		assertEquals(3, arr.size());
		assertEquals(1, arr.getInt(0));
		assertTrue(arr.getBoolean(1));

		JsonArray nested = new JsonArray();
		nested.add(2.5d);
		arr.add(nested);
		assertEquals(2.5d, arr.getJsonArray(3).getDouble(0), 0d);
	}

	/** JsonObject remove/getDouble/getBoolean/optString/optDouble 回退。 */
	@Test
	public void testJsonObjectHelpers() {
		JsonObject o = new JsonObject();
		o.put("num", 1.5d);
		o.put("flag", true);
		o.put("str", "hello");
		assertEquals(1.5d, o.getDouble("num"), 0d);
		assertTrue(o.getBoolean("flag"));
		assertEquals("hello", o.optString("str"));
		// optString 命中非字符串键返回 null
		assertNull(o.optString("num"));
		// optDouble 缺失/非数字返回默认
		assertEquals(3.0d, o.optDouble("missing", 3.0d), 0d);
		assertEquals(3.0d, o.optDouble("str", 3.0d), 0d);
		// remove
		assertEquals("hello", o.remove("str").getAsString());
		assertNull(o.get("str"));
	}

	private static void assertThrowsAi(Runnable r) {
		try {
			r.run();
			fail("应抛出 AiException");
		} catch (AiException ex) {
			// 期望
		}
	}
}
