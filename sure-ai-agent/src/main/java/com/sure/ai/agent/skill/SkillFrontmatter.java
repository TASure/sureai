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

package com.sure.ai.agent.skill;

import java.util.Map;

/**
 * SKILL.md 解析中间产物：frontmatter 字段表 + 正文 Markdown。
 *
 * <p>字段值类型（手写 YAML 子集）：</p>
 * <ul>
 *   <li>{@link String}：标量（含引号剥离后的值）；</li>
 *   <li>{@code List<String>}：块列表（{@code - item} 缩进序列）；</li>
 *   <li>{@code Map<String, String>}：嵌套标量表（如 {@code metadata:} 下的键值）。</li>
 * </ul>
 *
 * @param fields frontmatter 字段（保持书写顺序）
 * @param body   闭合 {@code ---} 之后的 Markdown 正文
 * @author sureai
 * @since 2.9.0
 */
record SkillFrontmatter(Map<String, Object> fields, String body) {
}
