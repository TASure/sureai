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

package com.sure.ai.mcp.server.skill;

import java.util.List;

import com.sure.ai.internal.json.JsonObject;

/**
 * 技能清单条目（Skills over MCP 扩展，SEP-2640）：{@code {uri, frontmatter, resources}}。
 *
 * <p>其中 {@code resources} 有两种形态：资源数可枚举时为
 * {@code [{uri,digest,size}, ...]}；资源为动态/海量时为字符串 {@code "dynamic"}。本类用
 * {@link #dynamic()} 区分：为 {@code true} 时序列化输出 {@code "dynamic"}，否则输出
 * {@link #resources()} 列表。</p>
 *
 * @param uri         技能根 URI（如 {@code skill://pdf-processing}）
 * @param frontmatter SKILL.md 的 YAML frontmatter 键值对象（至少含 name/description）
 * @param resources   静态资源清单（{@link #dynamic()} 为 false 时生效）
 * @param dynamic     true 表示资源为动态枚举，序列化为 {@code "dynamic"}
 * @author sureai
 * @since 2.9.0
 */
public record McpSkillInfo(String uri, JsonObject frontmatter,
		List<McpSkillResource> resources, boolean dynamic) {

	/**
	 * 紧凑构造：基础校验与防御性拷贝。
	 */
	public McpSkillInfo {
		if (uri == null || uri.isBlank()) {
			throw new IllegalArgumentException("skill uri 不能为空");
		}
		resources = resources == null ? List.of() : List.copyOf(resources);
	}

	/**
	 * 静态资源清单工厂。
	 *
	 * @param uri         技能 URI
	 * @param frontmatter frontmatter
	 * @param resources   资源清单
	 * @return 技能条目
	 */
	public static McpSkillInfo of(String uri, JsonObject frontmatter, List<McpSkillResource> resources) {
		return new McpSkillInfo(uri, frontmatter, resources, false);
	}

	/**
	 * 动态资源工厂（resources 序列化为 {@code "dynamic"}）。
	 *
	 * @param uri         技能 URI
	 * @param frontmatter frontmatter
	 * @return 技能条目
	 */
	public static McpSkillInfo dynamic(String uri, JsonObject frontmatter) {
		return new McpSkillInfo(uri, frontmatter, List.of(), true);
	}
}
