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

/**
 * 标准工具注解 {@code ToolAnnotations}（MCP 2026-07-28 工具规范）。
 *
 * <p>用于在 {@code tools/list} 中向客户端描述工具的行为特征，全部字段均为<b>提示</b>性质，
 * 不构成强制约束（强制约束由服务端自行实现）。</p>
 *
 * <p>规范依据：<a href="https://modelcontextprotocol.io/specification/2026-07-28/server/tools">
 * MCP Tools 规范</a>——{@code annotations} 字段含 {@code title}/{@code readOnlyHint}/
 * {@code destructiveHint}/{@code idempotentHint}/{@code openWorldHint}。非标准元数据（如标签、
 * 来源、风险级别私有取值）不属于标准注解，须放入 {@code _meta}，不得伪造核心字段。</p>
 *
 * @param title          人类可读的工具标题（可空）
 * @param readOnlyHint   只读提示：不修改任何外部状态（可空表示未声明）
 * @param destructiveHint 破坏性提示：可能执行删除/覆盖等不可逆操作（可空）
 * @param idempotentHint 幂等提示：相同参数重复执行效果一致（可空）
 * @param openWorldHint  开放世界提示：与外部实体交互（网络/第三方服务）（可空）
 * @author sureai
 * @since 2.9.0
 */
public record McpToolAnnotations(String title, Boolean readOnlyHint, Boolean destructiveHint,
		Boolean idempotentHint, Boolean openWorldHint) {

	/**
	 * 紧凑构造：允许全空（表示不声明任何注解）。
	 */
	public McpToolAnnotations {
		// 全字段可空，无可校验项；保留紧凑构造以便未来扩展
	}

	/**
	 * 仅设置标题的便捷工厂。
	 *
	 * @param title 标题
	 * @return 注解
	 */
	public static McpToolAnnotations title(String title) {
		return new McpToolAnnotations(title, null, null, null, null);
	}

	/**
	 * @return 新建 Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 注解 Builder。
	 *
	 * @author sureai
	 * @since 2.9.0
	 */
	public static final class Builder {

		private String title;
		private Boolean readOnlyHint;
		private Boolean destructiveHint;
		private Boolean idempotentHint;
		private Boolean openWorldHint;

		private Builder() {
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
		 * @param readOnly 只读提示
		 * @return this
		 */
		public Builder readOnlyHint(boolean readOnly) {
			this.readOnlyHint = readOnly;
			return this;
		}

		/**
		 * @param destructive 破坏性提示
		 * @return this
		 */
		public Builder destructiveHint(boolean destructive) {
			this.destructiveHint = destructive;
			return this;
		}

		/**
		 * @param idempotent 幂等提示
		 * @return this
		 */
		public Builder idempotentHint(boolean idempotent) {
			this.idempotentHint = idempotent;
			return this;
		}

		/**
		 * @param openWorld 开放世界提示
		 * @return this
		 */
		public Builder openWorldHint(boolean openWorld) {
			this.openWorldHint = openWorld;
			return this;
		}

		/**
		 * @return 不可变注解
		 */
		public McpToolAnnotations build() {
			return new McpToolAnnotations(this.title, this.readOnlyHint,
				this.destructiveHint, this.idempotentHint, this.openWorldHint);
		}
	}
}
