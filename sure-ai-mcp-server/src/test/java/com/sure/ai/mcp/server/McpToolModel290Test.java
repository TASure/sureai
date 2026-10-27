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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * 2.9.0 工具模型扩展测试：{@link McpToolAnnotations}、{@link McpServerTool} Builder/七参构造。
 *
 * @author sureai
 * @since 2.9.0
 */
public class McpToolModel290Test {

	/** 注解 Builder 链式输出不可变记录。 */
	@Test
	public void annotationsBuilder() {
		McpToolAnnotations a = McpToolAnnotations.builder()
			.title("计算器")
			.readOnlyHint(true)
			.idempotentHint(true)
			.build();
		assertEquals("计算器", a.title());
		assertEquals(Boolean.TRUE, a.readOnlyHint());
		assertEquals(Boolean.TRUE, a.idempotentHint());
		assertNull(a.destructiveHint());
		assertNull(a.openWorldHint());
	}

	/** title 工厂。 */
	@Test
	public void annotationsTitleFactory() {
		assertEquals("T", McpToolAnnotations.title("T").title());
	}

	/** 工具 Builder 全字段输出。 */
	@Test
	public void toolBuilderFullFields() {
		JsonObject meta = Json.object();
		meta.put("tags", "math");
		McpServerTool tool = McpServerTool.builder("calc", args -> new McpToolResult(false, List.of("1")))
			.description("计算")
			.inputSchema(Json.object())
			.title("计算器")
			.annotations(McpToolAnnotations.builder().readOnlyHint(true).build())
			.meta(meta)
			.build();
		assertEquals("calc", tool.name());
		assertEquals("计算", tool.description());
		assertEquals("计算器", tool.title());
		assertEquals(Boolean.TRUE, tool.annotations().readOnlyHint());
		assertEquals("math", tool.meta().optString("tags", null));
	}

	/** Builder 省略 schema 时 apply 传 null arguments 不抛错。 */
	@Test
	public void toolBuilderMinimal() {
		McpServerTool tool = McpServerTool.builder("ping", args -> new McpToolResult(false, List.of("pong")))
			.build();
		assertNull(tool.title());
		assertNull(tool.annotations());
		assertNull(tool.meta());
		assertEquals("{}", Json.stringify(tool.inputSchema()));
		assertEquals("pong", tool.apply(null).textContents().get(0));
	}

	/** 七参构造空 schema 退化。 */
	@Test
	public void sevenArgConstructorNullSchema() {
		McpServerTool tool = new McpServerTool("x", "d", null,
			args -> new McpToolResult(false, List.of()), null, null, null);
		assertEquals("{}", Json.stringify(tool.inputSchema()));
	}

	/** 名称/处理器为空时拒绝。 */
	@Test
	public void builderRejectsNulls() {
		assertThrows(NullPointerException.class,
			() -> McpServerTool.builder(null, args -> new McpToolResult(false, List.of())));
		assertThrows(NullPointerException.class,
			() -> McpServerTool.builder("x", null));
		assertThrows(NullPointerException.class,
			() -> new McpServerTool(null, null, null, args -> new McpToolResult(false, List.of())));
	}

	/** 四参旧构造器保持兼容。 */
	@Test
	public void legacyConstructorStillWorks() {
		McpServerTool tool = new McpServerTool("legacy", "旧", Json.object(),
			args -> new McpToolResult(false, List.of(Objects.toString(args))));
		assertNull(tool.title());
		assertNull(tool.annotations());
		assertNull(tool.meta());
		assertTrue(tool.apply(Json.object()).textContents().get(0).startsWith("{"));
	}
}
