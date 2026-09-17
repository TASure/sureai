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

package com.sure.ai.agent.tool;

/**
 * 工具执行处理器：接收模型返回的结构化参数，返回纯文本结果。
 *
 * <p>参数已由编排器从 {@code argumentsJson} 解析为 {@link com.sure.ai.internal.json.JsonObject}，
 * 处理器直接读取字段即可。返回值会作为 {@code tool} 角色消息回灌模型。</p>
 *
 * <p>实现可抛出任意异常，编排器（ReActAgent）会捕获并把异常信息回灌模型，
 * 不会中断编排循环。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
@FunctionalInterface
public interface ToolHandler {

	/**
	 * 执行工具。
	 *
	 * @param arguments 解析后的参数对象（模型 argumentsJson）
	 * @return 工具结果文本（回灌给模型）
	 * @throws Exception 执行异常，由编排器捕获并回灌模型
	 */
	String execute(com.sure.ai.internal.json.JsonObject arguments) throws Exception;
}
