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
package com.sure.ai.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * {@link McpServerTool} 补测：覆盖 inputSchema 为 null 时退化、apply(null) 退化为空参数。
 *
 * @author sureai
 * @since 2.6.0
 */
public class McpServerToolTest {

	/** inputSchema 传 null 时退化为空对象。 */
	@Test
	public void nullInputSchemaDefaultsToEmptyObject() {
		McpServerTool tool = new McpServerTool("t", "d", null, args -> new McpToolResult(false, List.of("x")));
		assertNotNull(tool.inputSchema());
		assertTrue(tool.inputSchema().size() == 0);
	}

	/** apply(null) 时处理器收到空参数对象。 */
	@Test
	public void applyNullArgumentsDefaultsToEmpty() {
		JsonObject[] captured = new JsonObject[1];
		McpServerTool tool = new McpServerTool("t", null, Json.object(), args -> {
			captured[0] = args;
			return new McpToolResult(false, List.of("ok"));
		});
		McpToolResult r = tool.apply(null);
		assertNotNull(captured[0]);
		assertTrue(captured[0].size() == 0);
		assertEquals("ok", r.asText());
	}

	/** 描述可为 null。 */
	@Test
	public void descriptionMayBeNull() {
		McpServerTool tool = new McpServerTool("t", null, Json.object(), args -> new McpToolResult(false, List.of()));
		assertNull(tool.description());
		assertEquals("t", tool.name());
	}
}
