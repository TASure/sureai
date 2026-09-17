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

import org.junit.Test;

/**
 * {@link JsonWriter} 单元测试。
 *
 * @author sureai
 * @since 0.1.0
 */
public class JsonWriterTest {

	/** 控制字符转义。 */
	@Test
	public void testControlChars() {
		JsonObject o = Json.object();
		o.put("k", "a\nb\tc\"d\\e");
		String s = Json.stringify(o);
		assertEquals("{\"k\":\"a\\nb\\tc\\\"d\\\\e\"}", s);
	}

	/** 中文不转义。 */
	@Test
	public void testChineseNotEscaped() {
		JsonObject o = Json.object();
		o.put("k", "中文");
		assertEquals("{\"k\":\"中文\"}", Json.stringify(o));
	}

	/** 空对象与空数组。 */
	@Test
	public void testEmpty() {
		assertEquals("{}", Json.stringify(Json.object()));
		assertEquals("[]", Json.stringify(Json.array()));
	}

	/** 数字格式：保留原始词法。 */
	@Test
	public void testNumberFormat() {
		JsonObject o = Json.object();
		o.put("a", 3);
		o.put("b", 2.5);
		assertEquals("{\"a\":3,\"b\":2.5}", Json.stringify(o));
	}

	/** 科学计数法词法保留。 */
	@Test
	public void testScientificKept() {
		JsonElement el = Json.parse("1e3");
		assertEquals("1e3", Json.stringify(el));
	}

	/** null 与布尔。 */
	@Test
	public void testNullBool() {
		JsonObject o = Json.object();
		o.put("a", JsonPrimitive.jsonNull());
		o.put("b", true);
		assertEquals("{\"a\":null,\"b\":true}", Json.stringify(o));
	}

	/** 普通 Java 类型入口。 */
	@Test
	public void testStringifyObject() {
		assertEquals("\"hi\"", Json.stringify("hi"));
		assertEquals("3.5", Json.stringify(3.5));
		assertEquals("true", Json.stringify(Boolean.TRUE));
		assertEquals("null", Json.stringify(null));
	}
}
