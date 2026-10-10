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

package com.sure.ai.util;

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
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.internal.json.JsonPrimitive;

/**
 * {@link JsonMapper} 反射映射边界测试。
 *
 * <p>覆盖私有构造器、非 record 拒绝、缺失原始类型默认值、List/Map/未知类型回退、
 * JsonObject 直通与不支持字段类型等分支。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public class JsonMapperEdgeTest {

	/** 嵌套 record。 */
	public record Nested(String label, int n) {
	}

	/** 含各原始类型与嵌套/集合字段的 record。 */
	public static class SampleHolder {
		private SampleHolder() {
		}
	}

	/** 全类型 record。 */
	public record Full(String name, int count, long id, double ratio, boolean flag,
			Nested nested, List<String> tags) {
	}

	/** 含不支持字段类型的 record。 */
	public record Unsupported(String name, java.io.File file) {
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		Constructor<JsonMapper> ctor = JsonMapper.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("应抛出 AssertionError");
		} catch (InvocationTargetException ex) {
			assertTrue(ex.getCause() instanceof AssertionError);
		}
	}

	/** JsonObject 直通。 */
	@Test
	public void testJsonObjectPassthrough() {
		JsonObject o = new JsonObject();
		o.put("a", 1);
		assertEquals(o, JsonMapper.fromJson(o, JsonObject.class));
	}

	/** 非 record 反序列化被拒绝。 */
	@Test
	public void testNonRecordRejected() {
		try {
			JsonMapper.fromJson(new JsonObject(), SampleHolder.class);
			fail("应抛出 AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("record"));
		}
	}

	/** 缺失原始类型字段时填默认值，嵌套与 List 正常映射。 */
	@Test
	public void testPrimitiveDefaultsAndNested() {
		JsonObject o = new JsonObject();
		o.put("name", "x");
		JsonObject nested = new JsonObject();
		nested.put("label", "inner");
		nested.put("n", 7);
		o.set("nested", nested);
		JsonArray tags = new JsonArray();
		tags.add("a");
		tags.add("b");
		o.set("tags", tags);

		Full f = JsonMapper.fromJson(o, Full.class);
		assertEquals("x", f.name());
		assertEquals(0, f.count());
		assertEquals(0L, f.id());
		assertEquals(0.0d, f.ratio(), 0d);
		assertEquals(false, f.flag());
		assertEquals("inner", f.nested().label());
		assertEquals(7, f.nested().n());
		assertEquals(List.of("a", "b"), f.tags());
	}

	/** toElement 对 null/JsonElement/Map/未知类型的回退。 */
	@Test
	public void testToElementFallback() {
		assertEquals(JsonPrimitive.jsonNull().toString(), Json.toElement(null).toString());

		JsonElement el = new JsonPrimitive("keep");
		assertEquals(el, Json.toElement(el));

		JsonObject fromMap = (JsonObject) Json.toElement(Map.of("k", "v"));
		assertEquals("v", fromMap.getString("k"));

		// 未知类型回退为 String.valueOf
		JsonElement fb = Json.toElement(12345L);
		assertTrue(fb.isNumber());
	}

	/** 不支持的字段类型抛业务异常。 */
	@Test
	public void testUnsupportedFieldType() {
		JsonObject o = new JsonObject();
		o.put("name", "x");
		o.put("file", "/tmp/x");
		try {
			JsonMapper.fromJson(o, Unsupported.class);
			fail("应抛出 AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("不支持"));
		}
	}

	/** toJsonObject null 返回 null。 */
	@Test
	public void testToJsonObjectNull() {
		assertNull(JsonMapper.toJsonObject(null));
	}

	/** toJson 委托：null / JsonElement 直通 / Map / 未知对象。 */
	@Test
	public void testToJsonDelegates() {
		assertEquals("null", JsonMapper.toJson(null).toString());
		JsonElement el = new JsonPrimitive("keep");
		assertEquals(el, JsonMapper.toJson(el));
		JsonObject fromMap = (JsonObject) JsonMapper.toJson(Map.of("k", "v"));
		assertEquals("v", fromMap.getString("k"));
		// 未知对象回退 String.valueOf
		assertEquals("123", JsonMapper.toJson(123).getAsString());
	}

	/** 含 raw List 与缺失 String 字段的 record：listElementType 退化、引用类型默认 null。 */
	public static class RawHolder {
		private RawHolder() {
		}
	}

	/** raw List record。 */
	public record RawListRecord(String title, @SuppressWarnings("rawtypes") List items) {
	}

	/** 缺失 String 字段时返回 null。 */
	@Test
	public void testRawListAndMissingString() {
		JsonObject o = new JsonObject();
		o.put("title", "t");
		// items 缺失 → listElementType 走 Object 回退
		RawListRecord r = JsonMapper.fromJson(o, RawListRecord.class);
		assertEquals("t", r.title());
		assertNull(r.items());
	}

	/** 规范构造器抛异常：反射异常被包装。 */
	public record Broken(String name) {
		/** 显式构造器，直接抛异常。 */
		public Broken(String name) {
			throw new IllegalStateException("ctor boom");
		}
	}

	@Test
	public void testRecordCtorThrows() {
		JsonObject o = new JsonObject();
		o.put("name", "x");
		try {
			JsonMapper.fromJson(o, Broken.class);
			fail("应抛出 AiException");
		} catch (AiException ex) {
			assertTrue(ex.getMessage().contains("反序列化失败"));
		}
	}
}
