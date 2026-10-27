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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link JsonSchemaGenerator} 单元测试：逐关键字断言类型映射 / required 策略 /
 * 嵌套 / 集合 / Map / 时间类型 / 循环引用 / 字符串输出合法性。
 *
 * @author sureai
 * @since 1.5.0
 */
public class JsonSchemaGeneratorTest {

	// ==================== 测试夹具 ====================

	/** 全原始类型持有类。 */
	public static class PrimitivesHolder {
		int i;
		long l;
		double d;
		boolean b;
		char c;
	}

	/** 全引用类型持有类。 */
	public static class WrappersHolder {
		String s;
		Integer i;
		Double d;
	}

	/** 颜色枚举。 */
	public enum Color {
		RED, GREEN, BLUE
	}

	/** 含枚举字段。 */
	public static class WithEnum {
		Color color;
	}

	/** 普通 POJO。 */
	public static class Address {
		String city;
		int zip;
	}

	/** record。 */
	public record Person(String name, int age) {
	}

	/** 嵌套 record：作者。 */
	public record Author(String name) {
	}

	/** 嵌套 record：书籍。 */
	public record Book(String title, Author author) {
	}

	/** 集合与数组。 */
	public static class CollectionsHolder {
		List<String> tags;
		int[] nums;
	}

	/** Map。 */
	public static class MapHolder {
		Map<String, Integer> counts;
	}

	/** 时间类型。 */
	public static class TimeHolder {
		Instant instant;
		LocalDate date;
		LocalDateTime dateTime;
		Date legacy;
	}

	/** 循环引用节点。 */
	public static class Node {
		String name;
		Node next;
	}

	/** 原始 / 引用混合。 */
	public static class Mixed {
		int age;
		String name;
		Long id;
		List<String> tags;
		boolean active;
		Double score;
	}

	// ==================== 用例 ====================

	/** 原始类型映射：int/long→integer，double→number，boolean→boolean，char→string。 */
	@Test
	public void testPrimitiveTypes() {
		JsonObject props = JsonSchemaGenerator.generate(PrimitivesHolder.class).getJsonObject("properties");
		assertEquals("integer", props.getJsonObject("i").getString("type"));
		assertEquals("integer", props.getJsonObject("l").getString("type"));
		assertEquals("number", props.getJsonObject("d").getString("type"));
		assertEquals("boolean", props.getJsonObject("b").getString("type"));
		assertEquals("string", props.getJsonObject("c").getString("type"));
	}

	/** 引用类型映射：String/Integer/Double → string/integer/number，且不在 required。 */
	@Test
	public void testStringAndWrapper() {
		JsonObject root = JsonSchemaGenerator.generate(WrappersHolder.class);
		JsonObject props = root.getJsonObject("properties");
		assertEquals("string", props.getJsonObject("s").getString("type"));
		assertEquals("integer", props.getJsonObject("i").getString("type"));
		assertEquals("number", props.getJsonObject("d").getString("type"));
		// 全部为引用类型：不应输出 required
		assertNull(root.get("required"));
	}

	/** 枚举：type=string + enum 数组列出全部常量。 */
	@Test
	public void testEnumType() {
		JsonObject color = JsonSchemaGenerator.generate(WithEnum.class).getJsonObject("properties")
			.getJsonObject("color");
		assertEquals("string", color.getString("type"));
		JsonArray enums = color.getJsonArray("enum");
		assertEquals(3, enums.size());
		assertEquals("RED", enums.getString(0));
		assertEquals("GREEN", enums.getString(1));
		assertEquals("BLUE", enums.getString(2));
	}

	/** 普通 POJO：type=object + properties + required（仅原始类型）。 */
	@Test
	public void testPojoProperties() {
		JsonObject root = JsonSchemaGenerator.generate(Address.class);
		assertEquals("object", root.getString("type"));
		JsonObject props = root.getJsonObject("properties");
		assertTrue(props.has("city"));
		assertTrue(props.has("zip"));
		JsonArray required = root.getJsonArray("required");
		assertEquals(1, required.size());
		assertEquals("zip", required.getString(0));
	}

	/** record：按组件生成 properties。 */
	@Test
	public void testRecordType() {
		JsonObject props = JsonSchemaGenerator.generate(Person.class).getJsonObject("properties");
		assertEquals("string", props.getJsonObject("name").getString("type"));
		assertEquals("integer", props.getJsonObject("age").getString("type"));
	}

	/** 嵌套对象：嵌套 properties 递归生成。 */
	@Test
	public void testNestedObject() {
		JsonObject book = JsonSchemaGenerator.generate(Book.class);
		JsonObject author = book.getJsonObject("properties").getJsonObject("author");
		assertEquals("object", author.getString("type"));
		assertEquals("string", author.getJsonObject("properties").getJsonObject("name").getString("type"));
	}

	/** List<String> 与 int[]：type=array + items。 */
	@Test
	public void testListAndArray() {
		JsonObject props = JsonSchemaGenerator.generate(CollectionsHolder.class).getJsonObject("properties");
		JsonObject tags = props.getJsonObject("tags");
		assertEquals("array", tags.getString("type"));
		assertEquals("string", tags.getJsonObject("items").getString("type"));
		JsonObject nums = props.getJsonObject("nums");
		assertEquals("array", nums.getString("type"));
		assertEquals("integer", nums.getJsonObject("items").getString("type"));
	}

	/** Map<String,Integer>：type=object + additionalProperties。 */
	@Test
	public void testMapType() {
		JsonObject counts = JsonSchemaGenerator.generate(MapHolder.class).getJsonObject("properties")
			.getJsonObject("counts");
		assertEquals("object", counts.getString("type"));
		assertEquals("integer", counts.getJsonObject("additionalProperties").getString("type"));
	}

	/** 时间类型：string + format。 */
	@Test
	public void testDateTimeTypes() {
		JsonObject props = JsonSchemaGenerator.generate(TimeHolder.class).getJsonObject("properties");
		assertEquals("string", props.getJsonObject("instant").getString("type"));
		assertEquals("date-time", props.getJsonObject("instant").getString("format"));
		assertEquals("date", props.getJsonObject("date").getString("format"));
		assertEquals("date-time", props.getJsonObject("dateTime").getString("format"));
		assertEquals("date-time", props.getJsonObject("legacy").getString("format"));
	}

	/** 循环引用：不抛异常，命中处降级为 {"type":"object"}，不再展开 properties。 */
	@Test
	public void testCircularReference() {
		JsonObject root = JsonSchemaGenerator.generate(Node.class);
		assertNotNull(root);
		JsonObject next = root.getJsonObject("properties").getJsonObject("next");
		assertEquals("object", next.getString("type"));
		// 循环引用节点不应继续展开 properties，否则会无限递归
		assertNull(next.get("properties"));
	}

	/** generateString：返回合法 JSON，可被 Json.parse 解析，且根节点带 $schema。 */
	@Test
	public void testGenerateString() {
		String json = JsonSchemaGenerator.generateString(Person.class);
		JsonElement parsed = Json.parse(json);
		assertTrue(parsed.isObject());
		JsonObject root = parsed.getAsJsonObject();
		assertTrue(root.getString("$schema").contains("2020-12"));
		assertEquals("string", root.getJsonObject("properties").getJsonObject("name").getString("type"));
	}

	/** 混合字段：required 数组只含原始类型（int age / boolean active）。 */
	@Test
	public void testMixedRequiredFields() {
		JsonObject root = JsonSchemaGenerator.generate(Mixed.class);
		JsonArray required = root.getJsonArray("required");
		assertEquals(2, required.size());
		assertEquals("age", required.getString(0));
		assertEquals("active", required.getString(1));
		assertFalse(required.toString().contains("name"));
		assertFalse(required.toString().contains("score"));
	}

	/** 美化输出：包含换行且仍可被 Json.parse 解析。 */
	@Test
	public void testGenerateStringPretty() {
		String pretty = JsonSchemaGenerator.generateStringPretty(Book.class);
		assertTrue(pretty.contains("\n"));
		JsonElement parsed = Json.parse(pretty);
		assertTrue(parsed.isObject());
		assertEquals("object", parsed.getAsJsonObject().getString("type"));
	}
}
