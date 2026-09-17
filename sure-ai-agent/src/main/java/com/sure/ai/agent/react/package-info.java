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
 * ReAct 编排子包。
 *
 * <p>{@code ReActAgent} 实现 Thought → Action → Observation 多工具循环：模型返回 tool_calls 时
 * 执行工具并把结果回灌，直到模型给出纯文本答案；支持最大轮数与总超时防护。</p>
 */
package com.sure.ai.agent.react;
