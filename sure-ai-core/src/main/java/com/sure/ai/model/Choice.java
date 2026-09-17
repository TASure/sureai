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

package com.sure.ai.model;

/**
 * 单条候选回复。
 *
 * @param index        下标
 * @param message      回复消息
 * @param finishReason 结束原因
 * @author sureai
 * @since 0.1.0
 */
public record Choice(int index, ChatMessage message, String finishReason) {

	/**
	 * 静态工厂。
	 *
	 * @param index        下标
	 * @param message      消息
	 * @param finishReason 结束原因
	 * @return 候选
	 */
	public static Choice of(int index, ChatMessage message, String finishReason) {
		return new Choice(index, message, finishReason);
	}
}
