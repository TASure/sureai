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

package com.sure.ai.mcp.server;

import com.sure.ai.client.SingletonHolder;

/**
 * {@link McpServer} 静态单例入口，与各平台 {@code XxxUtil} 范式一致。
 *
 * <pre>{@code
 * McpServerUtil.init(new McpServer().registerTool(SureAiTools.chatTool(openAiClient)));
 * McpServerUtil.startStdio();   // 阻塞在后台读线程，serve stdio
 * }</pre>
 *
 * <p>默认懒加载一个空 {@link McpServer}；生产用法通常先 {@link #init(McpServer)} 注册工具再启动传输。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
public final class McpServerUtil {

	private static final SingletonHolder<McpServer> HOLDER = new SingletonHolder<>(McpServer::new);

	private McpServerUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 显式设置单例 server。
	 *
	 * @param server server 实例
	 */
	public static void init(McpServer server) {
		HOLDER.set(server);
	}

	/**
	 * 获取单例 server（未初始化时懒加载空 server）。
	 *
	 * @return server
	 */
	public static McpServer server() {
		return HOLDER.get();
	}

	/**
	 * 重置单例。
	 */
	public static void resetServer() {
		HOLDER.reset();
	}

	/**
	 * 以 stdio 传输启动单例 server。
	 */
	public static void startStdio() {
		HOLDER.get().start(new StdioMcpServerTransport());
	}

	/**
	 * 以 HTTP 传输启动单例 server。
	 *
	 * @param port 端口（0 为随机）
	 * @return 启动的 HTTP 传输，便于读取实际端口
	 * @throws java.io.IOException 绑定端口失败
	 */
	public static HttpMcpServerTransport startHttp(int port) throws java.io.IOException {
		HttpMcpServerTransport http = new HttpMcpServerTransport(port, "/mcp");
		HOLDER.get().start(http);
		return http;
	}
}
