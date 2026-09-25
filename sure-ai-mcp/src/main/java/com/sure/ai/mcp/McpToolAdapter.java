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

package com.sure.ai.mcp;

import java.util.List;

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpTool;
import com.sure.ai.mcp.model.McpToolResult;
import com.sure.ai.model.ToolFunction;
import com.sure.tool.lang.Assert;

/**
 * MCP 工具适配器：把远端 MCP server 的全部工具包装成 sure-ai-agent 的 {@link ToolHandler}。
 *
 * <p>注册后，ReAct 编排器像调用本地工具一样调用远端 MCP 工具——模型给出的 argumentsJson
 * 原样透传给 MCP server，server 返回的 text content 拼好回灌模型。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class McpToolAdapter {

	private McpToolAdapter() {
		throw new AssertionError("No instances");
	}

	/**
	 * 把 MCP server 全部 tools 注册进 ToolRegistry。
	 *
	 * <p>同名工具会被覆盖（{@link ToolRegistry#register} 语义）。</p>
	 *
	 * @param client   已握手的 MCP 客户端
	 * @param registry 目标工具注册中心
	 * @return 实际注册的工具数
	 */
	public static int registerAllTools(McpClient client, ToolRegistry registry) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(registry, "registry must not be null");
		List<McpTool> tools = client.toolsList();
		for (McpTool tool : tools) {
			registry.register(toFunction(tool), toHandler(client, tool.name()));
		}
		return tools.size();
	}

	/** 把 McpTool 转为 ToolFunction（inputSchema 序列化为 JSON Schema 字符串）。 */
	private static ToolFunction toFunction(McpTool tool) {
		String schema;
		JsonElement inputSchema = tool.inputSchema();
		if (inputSchema == null) {
			schema = "{}";
		} else {
			schema = Json.stringify(inputSchema);
		}
		String desc = tool.description() == null ? "" : tool.description();
		return ToolFunction.of(tool.name(), desc, schema);
	}

	/** 构造 ToolHandler：透传 arguments 到 MCP server，返回拼接文本。 */
	private static ToolHandler toHandler(McpClient client, String toolName) {
		return (JsonObject arguments) -> {
			McpToolResult result = client.toolsCall(toolName, arguments);
			if (result.isError()) {
				return "MCP 工具执行出错: " + result.asText();
			}
			return result.hasText() ? result.asText() : "（无返回内容）";
		};
	}
}
