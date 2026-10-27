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

import java.util.List;

/**
 * 动态工具来源（2.9.0）：把外部工具目录（如 agent 侧 ToolCatalog、MCP 聚合市场）按需供给
 * {@link McpServer} 的 {@code tools/list} 与 {@code tools/call}。
 *
 * <p>注册式工具（{@link McpServer#registerTool(McpServerTool)}）是启动期静态表；本接口则允许工具清单
 * 在请求时动态计算（例如远程清单刷新、按标签过滤后的目录导出）。{@code tools/list} 每次会合并静态表与
 * 所有来源；{@code tools/call} 依次查找静态表与各来源，首个命中即执行。</p>
 *
 * <p>实现方应保证线程安全：{@code tools/list} 可被多请求线程并发调用。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
@FunctionalInterface
public interface McpToolSource {

	/**
	 * 提供当前可见的工具快照。
	 *
	 * @return 工具列表（不应为 null；空列表表示无工具）
	 */
	List<McpServerTool> tools();
}
