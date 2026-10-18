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

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.tool.lang.Assert;

/**
 * 待聚合的 MCP 工具载体：agent 模块不直接依赖 MCP 客户端类型，
 * 由 MCP 适配层（sure-ai-mcp 的 McpToolAdapter）把 {@code tools/list} 结果
 * 映射成本记录后批量喂给 {@link ToolCatalog}。
 *
 * <p>执行处理器已由适配层闭包化（内部持有对 MCP client 的 {@code tools/call} 调用），
 * 本记录不感知传输协议。</p>
 *
 * @param name        MCP 工具原始名（未加命名空间前缀）
 * @param description 工具描述（可空）
 * @param schemaJson  入参 JSON Schema 字符串（可空，缺省 {@code {}}）
 * @param handler     执行处理器（调用时透传参数到 MCP server）
 * @author sureai
 * @since 2.9.0
 */
public record ImportedMcpTool(String name, String description, String schemaJson, ToolHandler handler) {

	/** 紧凑构造：非空校验。 */
	public ImportedMcpTool {
		Assert.notBlank(name, "mcp tool name must not be blank");
		Assert.notNull(handler, "handler must not be null");
	}
}
