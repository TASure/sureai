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
 * 通用 LLM 评估模块（com.sure.ai.evals）：数据集 → 指标 → 批量执行 → 报告 → CI 断言 →
 * 录制回放与回归。仅依赖 {@code sure-ai-core}。
 *
 * <p>顶层提供执行入口 {@code EvalRunner}、便捷静态入口 {@code EvalUtil} 与 A/B 对比
 * {@code EvalComparison}；数据集、指标、报告、断言与录制分别位于对应子包。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
package com.sure.ai.evals;
