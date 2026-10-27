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

/**
 * Agent Skills 子包：按 Anthropic Agent Skills 规范（SKILL.md + YAML frontmatter）
 * 实现技能的本地目录 / classpath 发现、渐进式加载与工具化暴露。
 *
 * <p>核心组件：</p>
 * <ul>
 *   <li>{@link com.sure.ai.agent.skill.SkillManifest}：技能元数据（name/description 必填，
 *       license/compatibility/allowed-tools/metadata 为规范可选字段）；</li>
 *   <li>{@link com.sure.ai.agent.skill.Skill}：技能句柄，manifest 随加载就绪，
 *       正文（{@code loadBody()}）与资源（{@code loadResource(path)}）按需读取；</li>
 *   <li>{@link com.sure.ai.agent.skill.SkillLoader}：从本地目录或 classpath（含 jar）发现技能；</li>
 *   <li>{@link com.sure.ai.agent.skill.SkillTool}：把技能包装成 {@code ToolFunction} +
 *       {@code ToolHandler} 注册进 {@code ToolRegistry}。</li>
 * </ul>
 *
 * <p>本批<b>不执行</b>技能内 scripts/ 脚本（避免任意脚本执行），仅加载指令与文本资源。</p>
 */
package com.sure.ai.agent.skill;
