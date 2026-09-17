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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;
import org.junit.Test;

/**
 * {@link ToolArgumentValidator} 单元测试。
 */
public class ToolArgumentValidatorTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "required": ["city", "count"],
			  "properties": {
			    "city": { "type": "string" },
			    "count": { "type": "integer" },
			    "detail": { "type": "boolean" },
			    "tags": { "type": "array" },
			    "meta": { "type": "object" }
			  }
			}
			""";

	private final ToolArgumentValidator validator = new ToolArgumentValidator();

	private static JsonObject obj(String json) {
		return Json.parse(json).getAsJsonObject();
	}

	private static ToolFunction fn(String schema) {
		return ToolFunction.of("weather", "desc", schema);
	}

	@Test
	public void testValidArguments() {
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(SCHEMA),
				obj("{\"city\":\"西安\",\"count\":3,\"detail\":true,\"tags\":[\"a\"],\"meta\":{}}"));
		assertTrue(r.valid());
	}

	@Test
	public void testMissingRequired() {
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(SCHEMA),
				obj("{\"detail\":true}"));
		assertFalse(r.valid());
		String joined = String.join(";", r.errors());
		assertTrue(joined, joined.contains("city"));
		assertTrue(joined, joined.contains("count"));
	}

	@Test
	public void testTypeMismatch() {
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(SCHEMA),
				obj("{\"city\":123,\"count\":\"not-a-number\"}"));
		assertFalse(r.valid());
		String joined = String.join(";", r.errors());
		assertTrue(joined, joined.contains("city"));
		assertTrue(joined, joined.contains("count"));
	}

	@Test
	public void testEmptySchemaPassesAll() {
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(""),
				obj("{\"whatever\":1}"));
		assertTrue(r.valid());
	}

	@Test
	public void testNullSchemaPasses() {
		ToolArgumentValidator.ValidationResult r = validator.validate(fn(null),
				obj("{\"x\":1}"));
		assertTrue(r.valid());
	}

	@Test
	public void testInvalidJsonSchema() {
		ToolArgumentValidator.ValidationResult r = validator.validate(fn("{not json"),
				obj("{\"city\":\"西安\",\"count\":1}"));
		assertFalse(r.valid());
	}

	@Test
	public void testSchemaNotObject() {
		ToolArgumentValidator.ValidationResult r = validator.validate(fn("[1,2,3]"),
				obj("{}"));
		assertFalse(r.valid());
	}

	@Test
	public void testValidationResultOkIsImmutable() {
		ToolArgumentValidator.ValidationResult ok = ToolArgumentValidator.ValidationResult.ok();
		assertTrue(ok.valid());
		assertEquals(0, ok.errors().size());
	}
}
