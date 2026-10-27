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
 * 层级式委派（Agent-as-Tool / Handoff）子包。
 *
 * <p>与 {@link com.sure.ai.agent.orchestrator.AgentOrchestrator} 的
 * 「任务拆分 → 并行 fan-out → 聚合 fan-in」不同，本包提供<b>层级式委派</b>：
 * 一个父 Agent 把某个子任务委派给另一个 Agent 执行，子 Agent 的产出作为工具结果
 * 回传父 Agent，可逐级嵌套（父 → 子 → 孙）。</p>
 *
 * <p>两种语义：</p>
 * <ul>
 *   <li>{@link com.sure.ai.agent.delegation.AgentAsTool}：委派后父 Agent 继续推理，
 *       子 Agent 最终答案作为一条工具观察（observation）回灌父模型；</li>
 *   <li>{@link com.sure.ai.agent.delegation.HandoffAgent} /
 *       {@link com.sure.ai.agent.delegation.HandoffTool}：任务交接，控制权移交，
 *       子 Agent 答案即最终答案，父 Agent 不再继续推理。</li>
 * </ul>
 *
 * <p>本包自带最大委派深度限制、同链环检测、子事件冒泡、异常包装与可选超时；
 * 零第三方依赖，不改动既有 ReAct / PlanExecute / Orchestrator 公共签名。</p>
 */
package com.sure.ai.agent.delegation;
