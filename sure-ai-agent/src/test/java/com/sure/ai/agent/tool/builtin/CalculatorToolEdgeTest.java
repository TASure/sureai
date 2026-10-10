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
 * {@link CalculatorTool} 解析器边界补充测试。
 *
 * <p>覆盖缺参数、尾随 token、畸形数字、一元正号、缺右括号与空括号等分支。</p>
 */
public class CalculatorToolEdgeTest {

	private final CalculatorTool tool = new CalculatorTool();

	private String eval(String expression) {
		JsonObject args = new JsonObject();
		args.put("expression", expression);
		return tool.execute(args);
	}

	@Test
	public void testToToolFunctionDeclaresSchema() {
		assertEquals("calculator", CalculatorTool.toToolFunction().name());
	}

	@Test
	public void testMissingExpression() {
		assertTrue(tool.execute(new JsonObject()).startsWith("Error"));
	}

	@Test
	public void testTrailingTokens() {
		assertTrue(eval("1 2").startsWith("Error"));
	}

	@Test
	public void testMalformedNumber() {
		assertTrue(eval("1.2.3").startsWith("Error"));
	}

	@Test
	public void testUnaryPlus() {
		assertEquals("3", eval("+1+2"));
	}

	@Test
	public void testMissingClosingParenthesis() {
		assertTrue(eval("(1+2").startsWith("Error"));
	}

	@Test
	public void testEmptyParentheses() {
		assertTrue(eval("()").startsWith("Error"));
	}
}
