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
 * Agent 编排能力包。
 *
 * <p>在 sure-ai-core 的 Chat / Function Calling 原语之上，提供多工具循环编排：</p>
 * <ul>
 *   <li>{@code tool}：工具注册中心（{@code ToolRegistry}）、工具处理器（{@code ToolHandler}）、
 *       执行结果封装（{@code ToolExecutionResult}）与参数校验（{@code ToolArgumentValidator}）；</li>
 *   <li>{@code react}：{@code ReActAgent} 多工具循环编排器（Thought → Action → Observation）；</li>
 *   <li>{@code plan}：{@code PlanExecuteAgent}（骨架，未实现）；</li>
 *   <li>{@code AgentListener}：事件回调；{@code AgentUtil}：全局注册中心静态入口。</li>
 * </ul>
 *
 * <p>本模块仅依赖 sure-ai-core，与具体平台解耦：任意实现
 * {@code com.sure.ai.client.AiClient} 的客户端（OpenAI/Azure/通义/智谱/豆包等）均可驱动。</p>
 */
package com.sure.ai.agent;
