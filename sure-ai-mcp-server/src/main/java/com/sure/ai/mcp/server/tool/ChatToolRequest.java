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

package com.sure.ai.mcp.server.tool;

import java.util.List;

/**
 * {@code sureai.chat} 工具的入参模型，仅用于 {@link com.sure.ai.util.JsonSchemaGenerator} 推导 JSON Schema。
 *
 * <p>字段全部为引用类型，按 schema 生成器约定均为可选项；必填校验由处理器完成。</p>
 *
 * @param platform  客户端路由键（多 client 注册表模式下按 name 选 AiClient，可空）
 * @param model    模型名
 * @param prompt    便捷单轮用户输入（与 messages 二选一；为空时用 messages）
 * @param messages  多轮消息列表
 * @param temperature 采样温度（可空）
 * @param maxTokens 最大输出 token 数（可空）
 * @author sureai
 * @since 1.5.0
 */
public record ChatToolRequest(String platform, String model, String prompt,
		List<Message> messages, Double temperature, Integer maxTokens) {

	/**
	 * 一条对话消息。
	 *
	 * @param role    角色：system / user / assistant
	 * @param content 文本内容
	 */
	public record Message(String role, String content) {
	}
}
