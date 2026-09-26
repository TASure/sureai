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

import java.util.Objects;
import java.util.function.Function;

import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.mcp.model.McpToolResult;

/**
 * 一个可被 MCP 调用的服务端工具（注册式描述 + 处理器）。
 *
 * <p>不可变。{@code name} 在 {@link McpServer} 注册表内须唯一；{@code inputSchema} 为 JSON Schema 对象，
 * 推荐由 {@link com.sure.ai.util.JsonSchemaGenerator#generate(Class)} 从参数 record 自动生成；
 * {@code handler} 接收 {@code tools/call} 的 {@code arguments} 对象，返回 {@link McpToolResult}。</p>
 *
 * <p>处理器抛出的任何异常都会被 {@link McpServer} 捕获并转换为 {@code isError=true} 的工具结果，
 * 不会中断协议读循环。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
public final class McpServerTool {

	/** 工具名（{@code tools/list} 与 {@code tools/call} 的 name 字段）。 */
	private final String name;

	/** 工具描述（可空）。 */
	private final String description;

	/** 入参 JSON Schema。 */
	private final JsonObject inputSchema;

	/** 处理器：入参 arguments，出参工具结果。 */
	private final Function<JsonObject, McpToolResult> handler;

	/**
	 * 全参构造。
	 *
	 * @param name        工具名，不可为空
	 * @param description 工具描述，可空
	 * @param inputSchema 入参 JSON Schema，可空（空时退化为 {@code {"type":"object"}}）
	 * @param handler     处理器，不可为空
	 */
	public McpServerTool(String name, String description, JsonObject inputSchema,
			Function<JsonObject, McpToolResult> handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");
		this.name = name;
		this.description = description;
		this.inputSchema = inputSchema == null ? com.sure.ai.internal.json.Json.object() : inputSchema;
		this.handler = handler;
	}

	/**
	 * 工具名。
	 *
	 * @return 名称
	 */
	public String name() {
		return this.name;
	}

	/**
	 * 工具描述。
	 *
	 * @return 描述，可能为 null
	 */
	public String description() {
		return this.description;
	}

	/**
	 * 入参 JSON Schema。
	 *
	 * @return schema 对象
	 */
	public JsonObject inputSchema() {
		return this.inputSchema;
	}

	/**
	 * 执行工具。
	 *
	 * @param arguments {@code tools/call} 的 arguments 对象
	 * @return 工具结果
	 */
	public McpToolResult apply(JsonObject arguments) {
		return this.handler.apply(arguments == null ? com.sure.ai.internal.json.Json.object() : arguments);
	}
}
