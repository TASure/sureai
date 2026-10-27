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

/**
 * 技能内单个资源的描述（Skills over MCP 扩展，SEP-2640）。
 *
 * <p>对应 {@code skills/list} 结果中每个技能的 {@code resources[]} 条目：
 * {@code {uri, digest, size}}。{@code digest} 为 SHA-256 摘要（建议形如
 * {@code sha256:hex}），用于客户端缓存校验；{@code size} 为字节数。</p>
 *
 * @param uri    资源 URI（如 {@code skill://my-skill/SKILL.md}）
 * @param digest 内容摘要（{@code sha256:...}），可空（无法计算时）
 * @param size   资源字节数（不可为负）
 * @author sureai
 * @since 2.9.0
 */
public record McpSkillResource(String uri, String digest, long size) {

	/**
	 * 紧凑构造：基础校验。
	 */
	public McpSkillResource {
		if (uri == null || uri.isBlank()) {
			throw new IllegalArgumentException("resource uri 不能为空");
		}
		if (size < 0) {
			throw new IllegalArgumentException("resource size 不能为负");
		}
	}
}
