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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com.sure.ai.internal.json.JsonObject;

/**
 * {@link JsonSchemaGenerator} 类型映射与美化序列化边界测试。
 *
 * <p>覆盖布尔/UUID/URI 类型映射、raw 集合与 Map 退化、参数化元素类型解析、
 * static/transient 字段跳过以及 pretty 输出的空对象/空数组/嵌套数组分支。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
public class JsonSchemaGeneratorEdgeTest {

	/** 普通 POJO：含 raw 集合、参数化嵌套集合、static/transient 字段。 */
	public static class Bean {
		private String name;
		private static int staticField;
		private transient int transientField;
		@SuppressWarnings("rawtypes")
		private List rawList;
		@SuppressWarnings("rawtypes")
		private Map rawMap;
		private List<List<String>> matrix;

		/** 嵌套 bean，用于参数化元素类型。 */
		public static class Nested {
			private int value;
		}
	}

	/** 私有构造器不可实例化。 */
	@Test
	public void testPrivateCtor() throws Exception {
		Constructor<JsonSchemaGenerator> ctor = JsonSchemaGenerator.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		try {
			ctor.newInstance();
			fail("应抛出 AssertionError");
		} catch (InvocationTargetException ex) {
			assertTrue(ex.getCause() instanceof AssertionError);
		}
	}

	/** Boolean / UUID / URI 类型映射。 */
	@Test
	public void testScalarFormats() {
		JsonObject b = JsonSchemaGenerator.generate(Boolean.class);
		assertEquals("boolean", b.getString("type"));

		JsonObject u = JsonSchemaGenerator.generate(UUID.class);
		assertEquals("string", u.getString("type"));
		assertEquals("uuid", u.getString("format"));

		JsonObject uri = JsonSchemaGenerator.generate(URI.class);
		assertEquals("uri", uri.getString("format"));
	}

	/** raw 集合/Map 退化为 object，参数化元素类型正确解析，static/transient 被跳过。 */
	@Test
	public void testRawAndParameterizedAndSkippedFields() {
		JsonObject schema = JsonSchemaGenerator.generate(Bean.class);
		JsonObject props = schema.getJsonObject("properties");
		// 仅 name / rawList / rawMap / matrix 进入 properties
		assertTrue(props.has("name"));
		assertTrue(props.has("rawList"));
		assertTrue(props.has("rawMap"));
		assertTrue(props.has("matrix"));
		// static/transient 不出现
		assertTrue(props.get("staticField") == null);
		assertTrue(props.get("transientField") == null);
		// rawList items 退化为 object（Object 回退）
		JsonObject rawListSchema = props.getJsonObject("rawList");
		assertEquals("array", rawListSchema.getString("type"));
		assertEquals("object", rawListSchema.getJsonObject("items").getString("type"));
		// matrix 为 List<List<String>>，items 仍是 array（参数化嵌套解析）
		JsonObject matrixItems = props.getJsonObject("matrix").getJsonObject("items");
		assertEquals("array", matrixItems.getString("type"));
	}

	/** 枚举类型：type=string + enum 常量数组，触发 pretty 数组分支。 */
	public enum Color {
		RED, GREEN, BLUE
	}

	/** pretty 输出：对象缩进与 enum 数组分支。 */
	@Test
	public void testPrettyObjectAndArray() {
		// 含字段对象的 pretty 输出包含换行缩进
		String pretty = JsonSchemaGenerator.generateStringPretty(Bean.class);
		assertTrue(pretty.contains("\n"));
		assertTrue(pretty.contains("\"name\""));
		// 枚举 schema 的 pretty 输出走数组分支（enum 常量数组）
		String enumPretty = JsonSchemaGenerator.generateStringPretty(Color.class);
		assertTrue(enumPretty.contains("RED"));
		assertTrue(enumPretty.contains("\n"));
	}
}
