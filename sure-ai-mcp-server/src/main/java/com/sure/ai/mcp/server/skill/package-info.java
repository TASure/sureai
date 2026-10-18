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
 * Skills over MCP 扩展支持（官方扩展 {@code io.modelcontextprotocol/skills}，SEP-2640，
 * 2026-09-13 Final）：技能来源 SPI 与清单/资源模型。
 *
 * <p>本包只定义中立的供给接口（{@link com.sure.ai.mcp.server.skill.McpSkillSource}），
 * 不绑定 agent 侧 SkillLoader；协议编排在 {@link com.sure.ai.mcp.server.McpServer}
 * （{@code skills/list}、{@code skills/get}、{@code resources/read}、
 * {@code resources/directory/read}）。</p>
 *
 * @since 2.9.0
 */
package com.sure.ai.mcp.server.skill;
