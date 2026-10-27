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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * 2.9.0 动态工具来源 {@link McpToolSource} 聚合测试：tools/list 合并、去重、
 * 标准 annotations/私有 _meta 序列化、tools/call 路由。
 *
 * @author sureai
 * @since 2.9.0
 */
public class McpToolSourceTest {

	private static String req(long id, String method, JsonObject params) {
		JsonObject o = Json.object();
		o.put("jsonrpc", "2.0");
		o.put("id", id);
		o.put("method", method);
		if (params != null) {
			o.set("params", params);
		}
		return Json.stringify(o);
	}

	private static JsonObject resultOf(String response) {
		return Json.parse(response).getAsJsonObject().getJsonObject("result");
	}

	private static McpServerTool marketTool(String name, String reply) {
		JsonObject meta = Json.object();
		meta.put("source", "mcp");
		meta.put("server", "market");
		return McpServerTool.builder(name, args -> new McpToolResult(false, List.of(reply)))
			.description("市场工具 " + name)
			.title("标题-" + name)
			.annotations(McpToolAnnotations.builder()
				.readOnlyHint(true)
				.openWorldHint(true)
				.build())
			.meta(meta)
			.build();
	}

	/** tools/list 合并静态表与动态来源。 */
	@Test
	public void toolsListMergesStaticAndDynamic() {
		McpServer server = new McpServer()
			.registerTool(new McpServerTool("static", "本地", Json.object(),
				args -> new McpToolResult(false, List.of("s"))))
			.registerToolSource(() -> List.of(marketTool("dyn1", "d1"), marketTool("dyn2", "d2")));
		String resp = server.dispatch(req(1, "tools/list", null));
		JsonObject result = resultOf(resp);
		assertEquals(3, result.getJsonArray("tools").size());
		JsonObject dyn = result.getJsonArray("tools").get(1).getAsJsonObject();
		assertEquals("dyn1", dyn.getString("name"));
		assertEquals("标题-dyn1", dyn.getString("title"));
		JsonObject ann = dyn.getJsonObject("annotations");
		assertTrue(ann.getBoolean("readOnlyHint"));
		assertTrue(ann.getBoolean("openWorldHint"));
		// 私有元数据在 _meta，不污染核心字段
		assertEquals("market", dyn.getJsonObject("_meta").getString("server"));
		assertFalse(dyn.has("server"));
	}

	/** 同名工具静态优先，来源间先注册优先。 */
	@Test
	public void duplicateNamesPreferStaticThenFirstSource() {
		McpServer server = new McpServer()
			.registerTool(new McpServerTool("dup", "静态", Json.object(),
				args -> new McpToolResult(false, List.of("static"))))
			.registerToolSource(() -> List.of(
				new McpServerTool("dup", "来源1", Json.object(),
					args -> new McpToolResult(false, List.of("source1")))))
			.registerToolSource(() -> List.of(
				new McpServerTool("dup", "来源2", Json.object(),
					args -> new McpToolResult(false, List.of("source2")))));
		JsonObject params = Json.object();
		params.put("name", "dup");
		params.set("arguments", Json.object());
		JsonObject result = resultOf(server.dispatch(req(2, "tools/call", params)));
		assertEquals("static", result.getJsonArray("content").get(0).getAsJsonObject().getString("text"));
	}

	/** tools/call 可路由到动态来源的工具。 */
	@Test
	public void toolsCallRoutesToDynamicSource() {
		McpServer server = new McpServer()
			.registerToolSource(() -> List.of(marketTool("dyn", "hello-dyn")));
		JsonObject params = Json.object();
		params.put("name", "dyn");
		params.set("arguments", Json.object());
		JsonObject result = resultOf(server.dispatch(req(3, "tools/call", params)));
		assertFalse(result.getBoolean("isError"));
		assertEquals("hello-dyn",
			result.getJsonArray("content").get(0).getAsJsonObject().getString("text"));
	}

	/** 动态工具执行抛错仍为 isError 工具结果。 */
	@Test
	public void dynamicToolFailureIsErrorResult() {
		McpServer server = new McpServer()
			.registerToolSource(() -> List.of(McpServerTool.builder("boom",
				args -> {
					throw new IllegalStateException("炸");
				}).build()));
		JsonObject params = Json.object();
		params.put("name", "boom");
		params.set("arguments", Json.object());
		JsonObject result = resultOf(server.dispatch(req(4, "tools/call", params)));
		assertTrue(result.getBoolean("isError"));
		assertTrue(result.getJsonArray("content").get(0).getAsJsonObject().getString("text").contains("炸"));
	}

	/** null 来源注册被忽略；来源返回 null 清单容错。 */
	@Test
	public void nullSourcesAndNullListsTolerated() {
		McpServer server = new McpServer()
			.registerToolSource(null)
			.registerToolSource(() -> null)
			.registerToolSource(() -> List.of(marketTool("only", "x")));
		JsonObject result = resultOf(server.dispatch(req(5, "tools/list", null)));
		assertEquals(1, result.getJsonArray("tools").size());
	}

	/** 来源返回的列表含 null 元素时跳过。 */
	@Test
	public void nullToolEntriesSkipped() {
		McpServer server = new McpServer()
			.registerToolSource(() -> java.util.Arrays.asList(null, marketTool("ok", "y")));
		JsonObject result = resultOf(server.dispatch(req(6, "tools/list", null)));
		assertEquals(1, result.getJsonArray("tools").size());
	}

	/** 无状态 tools/list 动态工具同样带缓存提示。 */
	@Test
	public void statelessToolsListCarriesCacheHints() {
		McpServer server = new McpServer()
			.registerToolSource(() -> List.of(marketTool("dyn", "z")));
		JsonObject meta = Json.object();
		meta.put(McpServer.META_PROTOCOL_VERSION, McpServer.PROTOCOL_VERSION_STATELESS);
		JsonObject params = Json.object();
		params.set("_meta", meta);
		JsonObject result = resultOf(server.dispatch(req(7, "tools/list", params)));
		assertEquals(3_600_000L, result.optLong("ttlMs", 0L));
		assertEquals("public", result.getString("cacheScope"));
	}
}
