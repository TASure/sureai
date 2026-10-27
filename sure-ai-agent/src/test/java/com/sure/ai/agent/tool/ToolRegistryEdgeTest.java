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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.sure.ai.model.ToolFunction;

/**
 * {@link ToolRegistry} 清空与快照补充测试。
 */
public class ToolRegistryEdgeTest {

	@Test
	public void testClearEmptiesRegistry() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("a", "d", "{}"), args -> "a");
		assertEquals(1, registry.size());
		registry.clear();
		assertTrue(registry.isEmpty());
	}

	@Test
	public void testSnapshotIsImmutableView() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(ToolFunction.of("b", "d", "{}"), args -> "b");
		assertEquals(1, registry.snapshot().size());
	}
}
