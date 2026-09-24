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

package com.sure.ai.agent.tool.builtin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.internal.json.JsonObject;

/**
 * {@link DateTimeTool} 测试。
 */
public class DateTimeToolTest {

	private final DateTimeTool tool = new DateTimeTool();

	@Test
	public void testDefaultFormat() {
		String result = tool.execute(new JsonObject());
		assertFalse(result, result.isEmpty());
		assertFalse(result, result.startsWith("Error"));
	}

	@Test
	public void testCustomFormat() {
		JsonObject args = new JsonObject();
		args.put("format", "yyyy-MM-dd");
		String result = tool.execute(args);
		assertTrue(result, result.matches("\\d{4}-\\d{2}-\\d{2}"));
	}

	@Test
	public void testInvalidFormat() {
		JsonObject args = new JsonObject();
		args.put("format", "invalid pattern %%%%%%");
		String result = tool.execute(args);
		assertTrue(result, result.startsWith("Error: invalid date format"));
	}

	@Test
	public void testCustomZone() {
		JsonObject args = new JsonObject();
		args.put("format", "yyyy-MM-dd");
		args.put("zone", "UTC");
		String result = tool.execute(args);
		assertTrue(result, result.matches("\\d{4}-\\d{2}-\\d{2}"));
	}

	@Test
	public void testInvalidZone() {
		JsonObject args = new JsonObject();
		args.put("zone", "Not/AZone");
		String result = tool.execute(args);
		assertTrue(result, result.startsWith("Error: invalid time zone"));
	}
}
