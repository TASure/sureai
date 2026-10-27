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

/**
 * 技能来源（Skills over MCP 扩展，SEP-2640，2026-09-13 Final）：把上层加载的 Agent Skills
 * （{@code SKILL.md} 集合）供给 {@link com.sure.ai.mcp.server.McpServer} 暴露为
 * {@code skills/list}、{@code skills/get} 与 {@code resources/read}（{@code skill://} 方案）。
 *
 * <p>本接口<b>不依赖</b> agent 侧的 {@code SkillLoader}：任何能提供技能清单与正文读取能力的来源
 * （本地目录、classpath、远程目录）都可实现；agent 侧的适配由桥接层（如 examples）完成。</p>
 *
 * <p>URI 约定：技能根 URI 形如 {@code skill://<技能名>}，正文固定为
 * {@code skill://<技能名>/SKILL.md}，资源为 {@code skill://<技能名>/<正斜杠相对路径>}。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public interface McpSkillSource {

	/**
	 * 列出全部技能（分页由 {@link com.sure.ai.mcp.server.McpServer} 按 cursor 处理）。
	 *
	 * @return 技能清单（不应为 null）
	 */
	List<McpSkillInfo> listSkills();

	/**
	 * 按技能根 URI 获取单个技能（{@code skills/get}）。
	 *
	 * @param uri 技能根 URI（{@code skill://<name>}）
	 * @return 技能条目；未知 URI 返回 {@code null}（协议层映射为 {@code -32602}）
	 */
	McpSkillInfo getSkill(String uri);

	/**
	 * 读取技能资源文本（{@code resources/read}，含 SKILL.md 正文）。
	 *
	 * @param uri 资源 URI（{@code skill://<name>/<path>}）
	 * @return 文本内容；资源不存在返回 {@code null}（协议层映射为 {@code -32602}）
	 */
	String readResource(String uri);

	/**
	 * 是否支持 {@code resources/directory/read}（扩展可选能力）。
	 *
	 * @return 默认 false 不支持
	 */
	default boolean directoryRead() {
		return false;
	}

	/**
	 * 枚举目录下资源（仅 {@link #directoryRead()} 为 true 时调用）。
	 *
	 * @param uri 目录 URI
	 * @return 目录内资源清单
	 */
	default List<McpSkillResource> listDirectory(String uri) {
		throw new UnsupportedOperationException("directoryRead 未启用");
	}
}
