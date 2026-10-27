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

package com.sure.ai.mcp.transport;

import com.sure.ai.mcp.message.McpNotification;
import com.sure.ai.mcp.message.McpRequest;
import com.sure.ai.mcp.message.McpResponse;

/**
 * MCP 传输抽象：负责把 JSON-RPC 帧发给对端，并把响应帧按 id 配对回来。
 *
 * <p>两种内置实现：</p>
 * <ul>
 *   <li>{@link StdioMcpTransport}：子进程 stdin/stdout，NDJSON 每行一帧；</li>
 *   <li>{@link StreamableHttpMcpTransport}：JDK HttpClient POST 单端点，
 *       响应 {@code application/json} 直返、{@code text/event-stream} 按 SSE 聚合。</li>
 * </ul>
 *
 * <p>实现必须线程安全：{@link #sendRequest} 可被多线程并发调用（通过内部 id 配对）。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public interface McpTransport {

	/**
	 * 发送请求并阻塞等待对应 id 的响应。
	 *
	 * @param request 请求帧
	 * @return 响应帧
	 * @throws com.sure.ai.exception.AiException 传输错误 / 对端错误 / 超时
	 */
	McpResponse sendRequest(McpRequest request);

	/**
	 * 发送通知（无响应）。
	 *
	 * @param notification 通知帧
	 */
	void sendNotification(McpNotification notification);

	/** 关闭传输，释放底层资源（子进程 / HTTP 连接）。 */
	void close();

	/**
	 * 传输是否仍可用。
	 *
	 * @return 可发请求返回 true
	 */
	boolean isOpen();
}
