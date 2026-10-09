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
 * 声明式编排层的 Advisor 链：在「接口即服务」代理的一次调用路径上，
 * 以有序、可复用的中间件切面横切请求/响应。
 *
 * <p>本包与具体 AiService 接口解耦——Advisor 只操作 {@link com.sure.ai.model.ChatRequest} /
 * {@link com.sure.ai.model.ChatResponse}，因此既可用于 {@link com.sure.ai.framework.FrameworkUtil}
 * 的声明式代理，也可被其他编排器复用。内置四件套：</p>
 * <ul>
 *   <li>{@link com.sure.ai.framework.advisor.ToolCallingAdvisor}：自动工具循环
 *       （模型返回 tool_calls → 反射执行 @Tool 方法 → 结果回填 → 再调模型）；</li>
 *   <li>{@link com.sure.ai.framework.advisor.StructuredOutputValidationAdvisor}：
 *       结构化输出校验失败时把修正指令附加回请求自纠重试；</li>
 *   <li>{@link com.sure.ai.framework.advisor.LoggingAdvisor}：java.util.logging 结构化日志；</li>
 *   <li>{@link com.sure.ai.framework.advisor.SemanticCacheAdvisor}：语义缓存命中短路、未命中回填。</li>
 * </ul>
 *
 * <p><b>钩子语义</b>：{@link com.sure.ai.framework.advisor.Advisor#before} 正序执行、
 * {@link com.sure.ai.framework.advisor.Advisor#after} 逆序执行，
 * {@link com.sure.ai.framework.advisor.Advisor#around} 按注册顺序嵌套
 * （先注册的在外层）。典型注册顺序建议：语义缓存 → 日志 → 工具循环 → 结构化校验。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
package com.sure.ai.framework.advisor;
