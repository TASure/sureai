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

import com.sure.ai.internal.json.Json;
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
 * <p>2.9.0 起新增 {@code title}（标准标题）、{@link McpToolAnnotations}（标准行为提示）与
 * {@code _meta}（私有扩展元数据），通过 {@link #builder(String, Function)} 构造；旧的四参构造器保持
 * 二进制兼容。</p>
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

	/** 人类可读标题（可空）。 */
	private final String title;

	/** 标准工具注解（可空）。 */
	private final McpToolAnnotations annotations;

	/** 私有扩展元数据（{@code _meta}，可空）；非标准字段（标签/来源等）放这里。 */
	private final JsonObject meta;

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
		this(name, description, inputSchema, handler, null, null, null);
	}

	/**
	 * 七参全量构造（含标准标题、注解与私有元数据）。
	 *
	 * @param name        工具名，不可为空
	 * @param description 工具描述，可空
	 * @param inputSchema 入参 JSON Schema，可空
	 * @param handler     处理器，不可为空
	 * @param title       人类可读标题，可空
	 * @param annotations 标准工具注解，可空
	 * @param meta        {@code _meta} 私有扩展元数据，可空
	 */
	public McpServerTool(String name, String description, JsonObject inputSchema,
			Function<JsonObject, McpToolResult> handler,
			String title, McpToolAnnotations annotations, JsonObject meta) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");
		this.name = name;
		this.description = description;
		this.inputSchema = inputSchema == null ? Json.object() : inputSchema;
		this.handler = handler;
		this.title = title;
		this.annotations = annotations;
		this.meta = meta;
	}

	/**
	 * 创建 Builder（2.9.0 推荐入口）。
	 *
	 * @param name    工具名
	 * @param handler 处理器
	 * @return Builder
	 */
	public static Builder builder(String name, Function<JsonObject, McpToolResult> handler) {
		return new Builder(name, handler);
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
	 * 人类可读标题。
	 *
	 * @return 标题，可能为 null
	 */
	public String title() {
		return this.title;
	}

	/**
	 * 标准工具注解。
	 *
	 * @return 注解，可能为 null
	 */
	public McpToolAnnotations annotations() {
		return this.annotations;
	}

	/**
	 * 私有扩展元数据。
	 *
	 * @return {@code _meta} 对象，可能为 null
	 */
	public JsonObject meta() {
		return this.meta;
	}

	/**
	 * 执行工具。
	 *
	 * @param arguments {@code tools/call} 的 arguments 对象
	 * @return 工具结果
	 */
	public McpToolResult apply(JsonObject arguments) {
		return this.handler.apply(arguments == null ? Json.object() : arguments);
	}

	/**
	 * 工具 Builder（2.9.0）。
	 *
	 * @author sureai
	 * @since 2.9.0
	 */
	public static final class Builder {

		private final String name;
		private final Function<JsonObject, McpToolResult> handler;
		private String description;
		private JsonObject inputSchema;
		private String title;
		private McpToolAnnotations annotations;
		private JsonObject meta;

		private Builder(String name, Function<JsonObject, McpToolResult> handler) {
			this.name = Objects.requireNonNull(name, "name");
			this.handler = Objects.requireNonNull(handler, "handler");
		}

		/**
		 * @param description 工具描述
		 * @return this
		 */
		public Builder description(String description) {
			this.description = description;
			return this;
		}

		/**
		 * @param inputSchema 入参 JSON Schema
		 * @return this
		 */
		public Builder inputSchema(JsonObject inputSchema) {
			this.inputSchema = inputSchema;
			return this;
		}

		/**
		 * @param title 标题
		 * @return this
		 */
		public Builder title(String title) {
			this.title = title;
			return this;
		}

		/**
		 * @param annotations 标准注解
		 * @return this
		 */
		public Builder annotations(McpToolAnnotations annotations) {
			this.annotations = annotations;
			return this;
		}

		/**
		 * @param meta 私有扩展元数据（{@code _meta}）
		 * @return this
		 */
		public Builder meta(JsonObject meta) {
			this.meta = meta;
			return this;
		}

		/**
		 * @return 不可变工具
		 */
		public McpServerTool build() {
			return new McpServerTool(this.name, this.description, this.inputSchema, this.handler,
				this.title, this.annotations, this.meta);
		}
	}
}
