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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ToolExecutionResult} 不变量与工厂方法单元测试。
 */
public class ToolExecutionResultTest {

	@Test
	public void testSuccessCarriesOutputOnly() {
		ToolExecutionResult r = ToolExecutionResult.success("out");
		assertTrue(r.success());
		assertEquals("out", r.output());
		assertNull(r.error());
		assertEquals("out", r.payload());
	}

	@Test
	public void testFailureCarriesErrorOnly() {
		ToolExecutionResult r = ToolExecutionResult.failure("boom");
		assertTrue(!r.success());
		assertNull(r.output());
		assertEquals("boom", r.error());
		assertEquals("boom", r.payload());
	}

	@Test
	public void testSuccessMustNotCarryError() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> new ToolExecutionResult(true, "out", "err"));
		assertTrue(ex.getMessage().contains("error"));
	}

	@Test
	public void testFailureMustNotCarryOutput() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> new ToolExecutionResult(false, "out", "err"));
		assertTrue(ex.getMessage().contains("output"));
	}
}
