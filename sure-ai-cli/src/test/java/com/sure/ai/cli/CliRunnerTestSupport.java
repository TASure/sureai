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
package com.sure.ai.cli;

import java.util.List;

import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.Choice;

/**
 * CLI 测试共享小工具：构造固定文本的 {@link ChatResponse}。
 *
 * @author sureai
 * @since 2.6.0
 */
final class CliRunnerTestSupport {

	private CliRunnerTestSupport() {
	}

	/**
	 * 构造一条固定 assistant 文本的对话响应。
	 *
	 * @param text 回复文本
	 * @return 对话响应
	 */
	static ChatResponse fixedReply(String text) {
		Choice choice = Choice.of(0, ChatMessage.assistant(text), "stop");
		return ChatResponse.of("fake-id", "fake-model", List.of(choice), null, "{}");
	}
}
