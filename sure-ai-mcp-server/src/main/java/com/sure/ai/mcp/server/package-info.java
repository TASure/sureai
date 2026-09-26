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
 * MCP Server 模块：把 sureai 统一的多平台能力反向暴露为标准 Model Context Protocol Server。
 *
 * <p>核心入口：</p>
 * <ul>
 *   <li>{@link com.sure.ai.mcp.server.McpServer}：JSON-RPC 2.0 协议引擎 + 注册式工具表；</li>
 *   <li>{@link com.sure.ai.mcp.server.McpServerTool}：单个可被 MCP 调用的工具描述与处理器；</li>
 *   <li>{@link com.sure.ai.mcp.server.StdioMcpServerTransport}：stdio NDJSON 传输；</li>
 *   <li>{@link com.sure.ai.mcp.server.HttpMcpServerTransport}：Streamable HTTP 传输（JDK 内置 HttpServer）；</li>
 *   <li>{@link com.sure.ai.mcp.server.SureAiTools}：预置工具工厂，把 AiClient/EmbeddingClient/ImageClient 包成 MCP 工具；</li>
 *   <li>{@link com.sure.ai.mcp.server.McpServerUtil}：静态单例入口。</li>
 * </ul>
 *
 * <p><b>平台隔离红线</b>：本模块核心只依赖 sure-ai-core 与 sure-ai-mcp，<b>不</b>硬依赖任何平台模块。
 * 平台能力通过调用方注入 {@code AiClient}/{@code EmbeddingClient}/{@code ImageClient} 实例后才暴露。</p>
 *
 * <p><b>协议兼容</b>：同时兼容有状态规范 {@code 2025-06-18}（initialize 握手 + notifications/initialized +
 * Mcp-Session-Id）与无状态规范 {@code 2026-07-28}（按 {@code params._meta.io.modelcontextprotocol/protocolVersion}
 * 自适应，实现 {@code server/discover}、list 结果 {@code ttlMs}/{@code cacheScope}、结果 {@code resultType}）。
 * 无状态形态为<b>实验性部分实现</b>，未覆盖 MRTR、subscriptions/listen、OAuth 等扩展面。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
package com.sure.ai.mcp.server;
