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

/**
 * 服务端帧处理器：传输层把读到的一条原始 JSON-RPC 帧交给引擎，引擎返回要回写的帧。
 *
 * <p>返回 {@code null} 表示该帧是通知，无需回写。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
@FunctionalInterface
public interface McpFrameHandler {

	/**
	 * 处理一帧。
	 *
	 * @param rawLine 原始 JSON-RPC 文本
	 * @return 响应文本，或 {@code null}（通知）
	 */
	String handle(String rawLine);
}
