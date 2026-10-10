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

package com.sure.ai.agent.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;
import org.junit.Test;

/**
 * {@link ToolArgumentValidator} 类型分支补充测试。
 *
 * <p>覆盖 boolean/array/object 类型不匹配、未知字段跳过、无 type 字段跳过、
 * 非必填 null 放行等边界。</p>
 */
public class ToolArgumentValidatorEdgeTest {

	private final ToolArgumentValidator validator = new ToolArgumentValidator();

	private static JsonObject obj(String json) {
		return Json.parse(json).getAsJsonObject();
	}

	private static ToolFunction fn(String schema) {
		return ToolFunction.of("t", "d", schema);
	}

	@Test
	public void testBooleanMismatch() {
		String schema = """
				{"type":"object","properties":{"flag":{"type":"boolean"}}}
				""";
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"flag\":\"yes\"}"));
		assertFalse(r.valid());
		assertTrue(String.join(";", r.errors()),
				r.errors().get(0).contains("boolean"));
	}

	@Test
	public void testArrayMismatch() {
		String schema = """
				{"type":"object","properties":{"tags":{"type":"array"}}}
				""";
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"tags\":\"a,b\"}"));
		assertFalse(r.valid());
		assertTrue(r.errors().get(0).contains("array"));
	}

	@Test
	public void testObjectMismatch() {
		String schema = """
				{"type":"object","properties":{"meta":{"type":"object"}}}
				""";
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"meta\":42}"));
		assertFalse(r.valid());
		assertTrue(r.errors().get(0).contains("object"));
	}

	@Test
	public void testUnknownFieldSkipped() {
		String schema = """
				{"type":"object","properties":{"city":{"type":"string"}}}
				""";
		// unknownField 不在 properties → 跳过，不报错
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"city\":\"西安\",\"unknownField\":123}"));
		assertTrue(r.valid());
	}

	@Test
	public void testFieldSchemaNotObjectSkipped() {
		// props.field 存在但不是 JSON object → continue 跳过
		String schema = """
				{"type":"object","properties":{"city":"我不是对象"}}
				""";
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"city\":\"西安\"}"));
		assertTrue(r.valid());
	}

	@Test
	public void testFieldWithoutTypeSkipped() {
		String schema = """
				{"type":"object","properties":{"loose":{"description":"无 type"}}}
				""";
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"loose\":1}"));
		assertTrue(r.valid());
	}

	@Test
	public void testRequiredButNullPassesTypeCheck() {
		String schema = """
				{"type":"object","required":["city"],
				 "properties":{"city":{"type":"string"}}}
				""";
		// city 存在但为 null：required 缺失报错，类型检查放行 null
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"city\":null}"));
		assertFalse(r.valid());
		assertTrue(r.errors().get(0).contains("city"));
	}

	@Test
	public void testUnknownTypeIsIgnored() {
		String schema = """
				{"type":"object","properties":{"x":{"type":"weird"}}}
				""";
		// 未知类型不报错，交给业务层
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"x\":1}"));
		assertTrue(r.valid());
	}

	@Test
	public void testDescribeActualTypeBranches() {
		// 声明 string，实际值分别为 boolean / array / object → describe 走对应分支
		String schema = """
				{"type":"object","properties":{"a":{"type":"string"},
				 "b":{"type":"string"},"c":{"type":"string"}}}
				""";
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(schema),
				obj("{\"a\":true,\"b\":[1],\"c\":{}}"));
		assertFalse(r.valid());
		String joined = String.join(";", r.errors());
		assertTrue(joined, joined.contains("boolean"));
		assertTrue(joined, joined.contains("array"));
		assertTrue(joined, joined.contains("object"));
	}
}
