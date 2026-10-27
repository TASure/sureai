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

package com.sure.ai.minimax;

/**
 * MiniMax OpenAI 兼容端点当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方 OpenAI 兼容文档为准：
 * <a href="https://platform.minimaxi.com/docs/api-reference/text-openai-api">
 * https://platform.minimaxi.com/docs/api-reference/text-openai-api</a></p>
 *
 * <p>注：旧版 {@code abab6.5s-chat}、{@code MiniMax-Text-01} 属于 MiniMax 原生接口模型，
 * OpenAI 兼容端点当前主推 M 系列。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class MiniMaxModels {

	private MiniMaxModels() {
		throw new AssertionError("No instances");
	}

	/** MiniMax-M3：最新旗舰语言模型（1M 上下文，Agent 推理/工具调用/多模态输入）。 */
	public static final String MINIMAX_M3 = "MiniMax-M3";

	/** MiniMax-M2.7：M2 系列自我迭代模型（204,800 上下文，约 60 TPS）。 */
	public static final String MINIMAX_M2_7 = "MiniMax-M2.7";

	/** MiniMax-M2.5：高性价比复杂任务模型（204,800 上下文）。 */
	public static final String MINIMAX_M2_5 = "MiniMax-M2.5";
}
