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
package com.sure.ai.examples.mcp;

import java.util.ArrayList;
import java.util.List;

import com.sure.ai.agent.tool.catalog.ToolCatalog;
import com.sure.ai.agent.tool.catalog.ToolEntry;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.mcp.server.McpServerTool;
import com.sure.ai.mcp.server.McpToolSource;

/**
 * 工具市场桥接器：把 Agent 侧 {@link ToolCatalog} 的条目适配为 MCP Server
 * 的动态工具来源 {@link McpToolSource}。
 *
 * <p>市场私有元数据（标签、来源、风险级别、所属 MCP 服务）统一放入工具的 {@code _meta}，
 * 不污染 {@code name}/{@code description} 等标准字段；工具处理器抛出的异常
 * 映射为 {@code isError=true} 的标准工具结果。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class CatalogMcpToolSource implements McpToolSource {

	private final ToolCatalog catalog;

	/**
	 * 构造工具市场桥接器。
	 *
	 * @param catalog 工具市场（调用方完成注册后传入）
	 */
	public CatalogMcpToolSource(ToolCatalog catalog) {
		this.catalog = catalog;
	}

	@Override
	public List<McpServerTool> tools() {
		List<McpServerTool> result = new ArrayList<>();
		for (ToolEntry entry : this.catalog.list()) {
			result.add(toMcpTool(entry));
		}
		return result;
	}

	/**
	 * 市场条目转 MCP 工具。
	 *
	 * @param entry 市场条目
	 * @return MCP 工具
	 */
	private static McpServerTool toMcpTool(ToolEntry entry) {
		JsonObject schema;
		JsonElement parsed = Json.parse(entry.function().parameters());
		schema = parsed != null && parsed.isObject() ? parsed.getAsJsonObject() : Json.object();
		return McpServerTool.builder(entry.name(), args -> {
			try {
				String text = entry.handler().execute(args);
				return new McpToolResult(false, List.of(text == null ? "" : text));
			} catch (RuntimeException ex) {
				return new McpToolResult(true, List.of(ex.getMessage()));
			} catch (Exception ex) {
				// 受检异常同样映射为工具执行失败（不打断 JSON-RPC 应答）
				return new McpToolResult(true, List.of(ex.getMessage()));
			}
		})
			.description(entry.function().description())
			.inputSchema(schema)
			.meta(toMeta(entry))
			.build();
	}

	/**
	 * 市场元数据转 {@code _meta}（非标准字段一律走私有扩展）。
	 *
	 * @param entry 市场条目
	 * @return _meta 对象
	 */
	private static JsonObject toMeta(ToolEntry entry) {
		JsonObject meta = Json.object();
		List<String> tagList = new ArrayList<>(entry.metadata().tags());
		if (!tagList.isEmpty()) {
			JsonArray array = Json.array();
			for (String tag : tagList) {
				array.add(tag);
			}
			meta.set("tags", array);
		}
		meta.put("source", entry.metadata().source().name().toLowerCase());
		meta.put("riskLevel", entry.metadata().riskLevel().name());
		if (entry.metadata().version() != null) {
			meta.put("version", entry.metadata().version());
		}
		if (entry.metadata().server() != null) {
			meta.put("server", entry.metadata().server());
		}
		if (entry.metadata().docUrl() != null) {
			meta.put("docUrl", entry.metadata().docUrl());
		}
		return meta;
	}
}
