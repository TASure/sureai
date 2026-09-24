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
 * 多 Agent 编排子包：任务拆分、并行执行、结果聚合。
 *
 * <p>{@link com.sure.ai.agent.orchestrator.AgentOrchestrator} 把一个大任务经
 * {@link com.sure.ai.agent.orchestrator.TaskSplitter} 拆成子任务，提交到线程池由多个独立
 * {@code ReActAgent} 并行处理，单任务失败/超时被隔离为 {@code "[ERROR: ...]"} 文本，
 * 最终由 {@link com.sure.ai.agent.orchestrator.ResultAggregator} 聚合。</p>
 */
package com.sure.ai.agent.orchestrator;
