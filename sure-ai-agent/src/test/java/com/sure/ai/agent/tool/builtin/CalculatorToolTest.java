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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.internal.json.JsonObject;

/**
 * {@link CalculatorTool} 测试。
 */
public class CalculatorToolTest {

	private final CalculatorTool tool = new CalculatorTool();

	private String eval(String expression) {
		JsonObject args = new JsonObject();
		args.put("expression", expression);
		return tool.execute(args);
	}

	@Test
	public void testBasicArithmetic() {
		assertEquals("7", eval("1+2*3"));
		assertEquals("8", eval("10-4/2"));
	}

	@Test
	public void testParentheses() {
		assertEquals("9", eval("(1+2)*3"));
	}

	@Test
	public void testDecimal() {
		assertEquals("4.0", eval("1.5+2.5"));
	}

	@Test
	public void testNegative() {
		assertEquals("-2", eval("-5+3"));
	}

	@Test
	public void testDivisionByZero() {
		String result = eval("1/0");
		assertTrue(result, result.contains("division by zero"));
	}

	@Test
	public void testInvalidExpression() {
		String result = eval("abc");
		assertTrue(result, result.contains("invalid expression"));
	}

	@Test
	public void testSpaces() {
		assertEquals("3", eval(" 1 + 2 "));
	}
}
