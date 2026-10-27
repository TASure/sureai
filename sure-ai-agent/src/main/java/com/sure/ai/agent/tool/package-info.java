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
 * 工具注册与参数校验子包。
 *
 * <p>{@code ToolRegistry} 维护工具定义与处理器映射；{@code ToolHandler} 是函数式执行接口；
 * {@code ToolExecutionResult} 统一封装成功/失败；{@code ToolArgumentValidator} 按 JSON Schema
 * 做 required + 基础类型校验（简化范围，不含 pattern/enum 等约束）。</p>
 */
package com.sure.ai.agent.tool;
