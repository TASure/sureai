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

import java.util.List;
import java.util.Optional;

import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;
import org.junit.Test;

/**
 * {@link ToolRegistry} 单元测试。
 */
public class ToolRegistryTest {

	private static ToolFunction fn(String name) {
		return ToolFunction.of(name, "desc-" + name, "{\"type\":\"object\"}");
	}

	@Test
	public void testRegisterAndGet() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(fn("get_weather"), args -> "ok");
		assertEquals(1, registry.size());

		List<ToolSpec> specs = registry.getToolSpecs();
		assertEquals(1, specs.size());
		assertEquals("get_weather", specs.get(0).function().name());

		Optional<ToolHandler> h = registry.getHandler("get_weather");
		assertTrue(h.isPresent());
	}

	@Test
	public void testUnregister() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(fn("a"), args -> "1");
		assertTrue(registry.unregister("a"));
		assertFalse(registry.getHandler("a").isPresent());
		assertFalse(registry.unregister("a"));
		assertEquals(0, registry.size());
	}

	@Test
	public void testDuplicateRegisterOverrides() {
		ToolRegistry registry = new ToolRegistry();
		String[] first = new String[] { "old" };
		String[] second = new String[] { "new" };
		registry.register(fn("dup"), args -> first[0]);
		registry.register(fn("dup"), args -> second[0]);
		assertEquals(1, registry.size());
		Optional<ToolHandler> h = registry.getHandler("dup");
		assertTrue(h.isPresent());
	}

	@Test
	public void testEmptyRegistry() {
		ToolRegistry registry = new ToolRegistry();
		assertEquals(0, registry.size());
		assertTrue(registry.getToolSpecs().isEmpty());
		assertTrue(registry.isEmpty());
	}

	@Test
	public void testRegisterBySpec() {
		ToolRegistry registry = new ToolRegistry();
		ToolSpec spec = ToolSpec.of(fn("calc"));
		registry.register(spec, args -> "42");
		assertTrue(registry.getHandler("calc").isPresent());
		assertTrue(registry.getFunction("calc").isPresent());
	}
}
