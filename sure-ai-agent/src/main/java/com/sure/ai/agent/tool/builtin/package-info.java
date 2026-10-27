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
 * 内置工具包子包：开箱即用的 {@code ToolHandler} 实现。
 *
 * <p>{@link com.sure.ai.agent.tool.builtin.HttpTool} 基于 JDK HttpClient 发起 GET/POST；
 * {@link com.sure.ai.agent.tool.builtin.DateTimeTool} 返回格式化当前时间；
 * {@link com.sure.ai.agent.tool.builtin.CalculatorTool} 用自研递归下降解析器做白名单四则运算。
 * 每个工具都提供静态 {@code toToolFunction()} 供注册到 {@code ToolRegistry}。</p>
 */
package com.sure.ai.agent.tool.builtin;
