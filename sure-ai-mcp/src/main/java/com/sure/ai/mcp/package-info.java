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
 * MCP（Model Context Protocol）客户端模块。
 *
 * <p>纯 JDK 实现 JSON-RPC 2.0 消息帧、stdio 子进程与 Streamable HTTP 两种传输，
 * 完成 initialize 握手后调用 tools/resources/prompts 能力，并通过
 * {@link com.sure.ai.mcp.McpToolAdapter} 把远端工具一键注册进
 * {@link com.sure.ai.agent.tool.ToolRegistry}，与 ReActAgent 组合使用。</p>
 *
 * <p>运行期零第三方依赖：JSON 复用 {@code com.sure.ai.internal.json}，
 * SSE 复用 {@code com.sure.ai.internal.http}，HTTP 用 JDK {@link java.net.http.HttpClient}，
 * 进程管理用 {@link ProcessBuilder}。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
package com.sure.ai.mcp;
