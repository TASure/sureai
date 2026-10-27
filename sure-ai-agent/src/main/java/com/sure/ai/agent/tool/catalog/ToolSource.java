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

package com.sure.ai.agent.tool.catalog;

/**
 * 工具来源类型：本地内置 / 注册的工具，或经 MCP 协议接入的远端服务工具。
 *
 * @author sureai
 * @since 2.9.0
 */
public enum ToolSource {

	/** 本地工具：进程内直接注册的函数与处理器。 */
	LOCAL,

	/** MCP 工具：来自远端 MCP server，经 tools/list 发现、tools/call 执行。 */
	MCP
}
