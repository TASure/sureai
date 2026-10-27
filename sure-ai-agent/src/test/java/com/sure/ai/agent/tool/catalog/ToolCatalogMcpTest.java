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

package com.sure.ai.agent.tool.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * MCP 工具聚合：命名空间前缀、来源标注、批量冲突与边界。
 */
public class ToolCatalogMcpTest {

	private static ImportedMcpTool tool(String name, String desc) {
		return new ImportedMcpTool(name, desc, "{\"type\":\"object\"}", args -> "called:" + name);
	}

	@Test
	public void testNamespacedRegistrationCarriesSourceAndServer() {
		ToolCatalog catalog = new ToolCatalog();
		int n = catalog.registerMcpTools(
			List.of(tool("echo", "回显"), tool("add", "求和")),
			"File System Server", ConflictStrategy.OVERWRITE, true);
		assertEquals(2, n);
		ToolEntry echo = catalog.get("mcp.file_system_server.echo").orElseThrow();
		assertEquals(ToolSource.MCP, echo.metadata().source());
		assertEquals("File System Server", echo.metadata().server());
		assertEquals(RiskLevel.LOW, echo.metadata().riskLevel());
		assertEquals("mcp.file_system_server.echo", echo.name());
		ToolEntry add = catalog.get("mcp.file_system_server.add").orElseThrow();
		assertEquals("回显", echo.function().description());
		assertNull(add.metadata().version());
	}

	@Test
	public void testBareNamesWithoutNamespace() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.registerMcpTools(List.of(tool("echo", "e")), "svc", ConflictStrategy.OVERWRITE, false);
		assertTrue(catalog.get("echo").isPresent());
		assertTrue(catalog.get("mcp.svc.echo").isEmpty());
	}

	@Test
	public void testEmptyAndNullBatch() {
		ToolCatalog catalog = new ToolCatalog();
		assertEquals(0, catalog.registerMcpTools(List.of(), "s", null, false));
		assertEquals(0, catalog.registerMcpTools(null, "s", null, false));
		assertTrue(catalog.isEmpty());
	}

	@Test
	public void testConflictAcrossBatchesFailStrategy() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.registerMcpTools(List.of(tool("echo", "a")), "serverA", ConflictStrategy.OVERWRITE, false);
		assertThrows(IllegalStateException.class, () -> catalog.registerMcpTools(
			List.of(tool("echo", "b")), "serverB", ConflictStrategy.FAIL, false));
	}

	@Test
	public void testRenameAcrossBatches() {
		ToolCatalog catalog = new ToolCatalog();
		catalog.registerMcpTools(List.of(tool("echo", "a")), "serverA", ConflictStrategy.RENAME, false);
		catalog.registerMcpTools(List.of(tool("echo", "b")), "serverB", ConflictStrategy.RENAME, false);
		assertEquals(2, catalog.size());
		assertTrue(catalog.get("echo").isPresent());
		assertTrue(catalog.get("echo__1").isPresent());
	}

	@Test
	public void testNullSchemaAndDescriptionHandled() {
		ToolCatalog catalog = new ToolCatalog();
		ImportedMcpTool bare = new ImportedMcpTool("bare", null, null, args -> "x");
		catalog.registerMcpTools(List.of(bare), "srv", ConflictStrategy.OVERWRITE, true);
		ToolEntry e = catalog.get("mcp.srv.bare").orElseThrow();
		assertEquals("{}", e.function().parameters());
		assertEquals("", e.function().description());
	}

	@Test
	public void testBlankServerLabelRejected() {
		ToolCatalog catalog = new ToolCatalog();
		assertThrows(RuntimeException.class,
			() -> catalog.registerMcpTools(List.of(tool("a", "d")), "  ", null, true));
	}

	@Test
	public void testImportedToolValidation() {
		assertThrows(RuntimeException.class, () -> new ImportedMcpTool("  ", "d", "{}", args -> "x"));
		assertThrows(RuntimeException.class, () -> new ImportedMcpTool("a", "d", "{}", null));
	}
}
