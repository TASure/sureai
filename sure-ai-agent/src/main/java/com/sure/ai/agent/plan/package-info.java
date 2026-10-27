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
 * Plan-and-Execute 编排子包。
 *
 * <p>提供 {@code PlanExecuteAgent}：先全局规划（JSON 数组步骤）、再分步执行
 * （可带工具调用）、最后汇总产出最终答案。支持可选的
 * {@link com.sure.ai.agent.memory.ConversationMemory} 会话记忆。</p>
 */
package com.sure.ai.agent.plan;
