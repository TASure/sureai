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
 * RAG 评估能力包：RAGAS 风格的 LLM-as-judge 指标、轨迹存储与回放回归。
 *
 * <p>核心组件：</p>
 * <ul>
 *   <li>{@code RagTrace} / {@code TraceStore} / {@code InMemoryTraceStore} /
 *       {@code TraceSerializer}：轨迹模型、存储与 JSON 往返；</li>
 *   <li>{@code LlmJudge}：渲染提示词 → 调模型 → 容错解析的基类；</li>
 *   <li>{@code RagMetric} 与四个内置指标：{@code FaithfulnessMetric}、
 *       {@code ContextPrecisionMetric}、{@code ContextRecallMetric}、
 *       {@code AnswerRelevancyMetric}；</li>
 *   <li>{@code RagEvaluator} / {@code EvaluationResult} / {@code EvaluationThreshold} /
 *       {@code EvaluationAssertions}：评估编排、阈值断言（任务成功率回归）；</li>
 *   <li>{@code TraceReplay}：从存储或 JSON 回放历史轨迹，重跑评估做跨版本对比。</li>
 * </ul>
 *
 * <p>零新增依赖，仅使用 sure-core 与 rag 既有 SPI；公共 API 只增不改。</p>
 */
package com.sure.ai.rag.evaluation;
