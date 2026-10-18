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

import java.util.LinkedHashSet;
import java.util.Set;

import com.sure.tool.lang.Assert;

/**
 * 工具市场元数据：在 {@code ToolFunction}（声明）与 {@code ToolHandler}（执行）之外，
 * 补充标签、版本、风险级别、来源、服务端标识与文档链接等市场信息。
 *
 * <p>不可变对象，经 {@link Builder} 构造；构造时做非空 / 归一化校验。
 * 标签统一去空白、转小写、去重后以不可变集合保存。</p>
 *
 * <p>字段说明：{@code name} 工具名（应与 ToolFunction.name 一致）；
 * {@code description} 市场侧补充描述（可空，缺省为空串）；{@code tags} 标签集合
 * （不可变、小写归一化）；{@code version} 工具版本（可空）；{@code riskLevel} 风险级别
 * （非空，缺省 {@link RiskLevel#LOW}）；{@code source} 来源类型（非空，缺省
 * {@link ToolSource#LOCAL}）；{@code server} 来源服务端标识（MCP server 名 /
 * 命名空间，本地工具可空）；{@code docUrl} 可选文档 / 说明 URL（可空）。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class ToolMetadata {

	private final String name;
	private final String description;
	private final Set<String> tags;
	private final String version;
	private final RiskLevel riskLevel;
	private final ToolSource source;
	private final String server;
	private final String docUrl;

	private ToolMetadata(Builder b) {
		Assert.notBlank(b.name, "metadata.name must not be blank");
		this.name = b.name.trim();
		this.description = b.description == null ? "" : b.description;
		this.tags = Set.copyOf(b.tags);
		this.version = b.version;
		this.riskLevel = b.riskLevel == null ? RiskLevel.LOW : b.riskLevel;
		this.source = b.source == null ? ToolSource.LOCAL : b.source;
		this.server = b.server;
		this.docUrl = b.docUrl;
	}

	/**
	 * 以工具名启动 Builder。
	 *
	 * @param name 工具名（非空）
	 * @return Builder
	 */
	public static Builder builder(String name) {
		return new Builder(name);
	}

	/**
	 * 从既有元数据复制出新 Builder。
	 *
	 * @param copy 源元数据
	 * @return Builder
	 */
	public static Builder from(ToolMetadata copy) {
		Assert.notNull(copy, "copy must not be null");
		Builder b = new Builder(copy.name);
		b.description = copy.description;
		b.tags.addAll(copy.tags);
		b.version = copy.version;
		b.riskLevel = copy.riskLevel;
		b.source = copy.source;
		b.server = copy.server;
		b.docUrl = copy.docUrl;
		return b;
	}

	/**
	 * 转换为可修改的 Builder。
	 *
	 * @return Builder
	 */
	public Builder toBuilder() {
		return from(this);
	}

	/** 工具名。 */
	public String name() {
		return this.name;
	}

	/** 市场侧补充描述。 */
	public String description() {
		return this.description;
	}

	/** 不可变标签集合。 */
	public Set<String> tags() {
		return this.tags;
	}

	/** 工具版本（可空）。 */
	public String version() {
		return this.version;
	}

	/** 风险级别。 */
	public RiskLevel riskLevel() {
		return this.riskLevel;
	}

	/** 来源类型。 */
	public ToolSource source() {
		return this.source;
	}

	/** 来源服务端标识（本地工具可空）。 */
	public String server() {
		return this.server;
	}

	/** 文档 / 说明 URL（可空）。 */
	public String docUrl() {
		return this.docUrl;
	}

	/**
	 * 元数据 Builder。
	 */
	public static final class Builder {

		private String name;
		private String description;
		private final Set<String> tags = new LinkedHashSet<>();
		private String version;
		private RiskLevel riskLevel = RiskLevel.LOW;
		private ToolSource source = ToolSource.LOCAL;
		private String server;
		private String docUrl;

		private Builder(String name) {
			this.name = name;
		}

		/**
		 * 覆盖工具名（RENAME 改名场景使用）。
		 *
		 * @param name 新工具名（非空）
		 * @return this
		 */
		public Builder name(String name) {
			Assert.notBlank(name, "metadata.name must not be blank");
			this.name = name;
			return this;
		}

		/**
		 * 设置市场侧补充描述。
		 *
		 * @param description 描述
		 * @return this
		 */
		public Builder description(String description) {
			this.description = description;
			return this;
		}

		/**
		 * 追加单个标签（归一化：去空白、转小写、跳过空串）。
		 *
		 * @param tag 标签
		 * @return this
		 */
		public Builder addTag(String tag) {
			if (tag != null) {
				String normalized = tag.trim().toLowerCase();
				if (!normalized.isEmpty()) {
					this.tags.add(normalized);
				}
			}
			return this;
		}

		/**
		 * 批量追加标签。
		 *
		 * @param tags 标签集合（可空）
		 * @return this
		 */
		public Builder addTags(Iterable<String> tags) {
			if (tags != null) {
				for (String tag : tags) {
					addTag(tag);
				}
			}
			return this;
		}

		/**
		 * 设置工具版本。
		 *
		 * @param version 版本号
		 * @return this
		 */
		public Builder version(String version) {
			this.version = version;
			return this;
		}

		/**
		 * 设置风险级别（null 回退 LOW）。
		 *
		 * @param riskLevel 风险级别
		 * @return this
		 */
		public Builder riskLevel(RiskLevel riskLevel) {
			this.riskLevel = riskLevel == null ? RiskLevel.LOW : riskLevel;
			return this;
		}

		/**
		 * 设置来源类型（null 回退 LOCAL）。
		 *
		 * @param source 来源
		 * @return this
		 */
		public Builder source(ToolSource source) {
			this.source = source == null ? ToolSource.LOCAL : source;
			return this;
		}

		/**
		 * 设置来源服务端标识（MCP server 名 / 命名空间）。
		 *
		 * @param server 服务端标识
		 * @return this
		 */
		public Builder server(String server) {
			this.server = server;
			return this;
		}

		/**
		 * 设置文档 / 说明 URL。
		 *
		 * @param docUrl 文档 URL
		 * @return this
		 */
		public Builder docUrl(String docUrl) {
			this.docUrl = docUrl;
			return this;
		}

		/**
		 * 构造不可变元数据。
		 *
		 * @return ToolMetadata
		 */
		public ToolMetadata build() {
			return new ToolMetadata(this);
		}
	}
}
