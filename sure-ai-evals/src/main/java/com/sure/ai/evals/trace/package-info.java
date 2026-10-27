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
 * 通用对话录制与回放、指标回归：{@code EvalTrace}、{@code RecordingClient}（装饰
 * {@code AiClient} 录制 JSONL / 离线回放）、{@code TraceRegression}（基线对比）。
 *
 * <p>与 {@code sure-ai-rag} 的 RAG 专用 TraceStore / TraceReplay 定位不同、互不依赖。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
package com.sure.ai.evals.trace;
