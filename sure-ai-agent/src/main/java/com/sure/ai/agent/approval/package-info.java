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
 * 人在回路（Human-In-The-Loop, HITL）审批子包。
 *
 * <p>Agent 在执行高危/写操作工具前，可通过 {@link com.sure.ai.agent.approval.ApprovalGate}
 * 发起审批：{@link com.sure.ai.agent.approval.ApprovalPolicy} 决定“要不要审批”，
 * {@link com.sure.ai.agent.approval.ApprovalHandler} 决定“批不批”。
 * 拒绝/超时不执行工具，而是把提示文本回灌模型自我修正。</p>
 *
 * <p>预置策略：全部审批 / 永不审批 / 按工具名 / 按高危规则；
 * 预置处理器：自动通过 / 自动拒绝 / 控制台交互 / 超时包装。</p>
 */
package com.sure.ai.agent.approval;
