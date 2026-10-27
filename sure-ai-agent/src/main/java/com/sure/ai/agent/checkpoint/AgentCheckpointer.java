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

package com.sure.ai.agent.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.sure.ai.agent.memory.InMemoryConversationMemory;
import com.sure.ai.agent.react.ReActAgent;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.model.ChatMessage;
import com.sure.tool.lang.Assert;

/**
 * 检查点门面：创建 / 保存 / 加载 / 从检查点恢复 {@link ReActAgent} 会话。
 *
 * <p><b>恢复语义（重放式）：</b>从检查点恢复 = 把检查点历史中属于「记忆与当轮」的部分
 * 灌入一个新的 {@link InMemoryConversationMemory}，再用 {@link ReActAgent#withMemory}
 * 构造一个带历史的编排器继续 {@code run()}。模型会看到完整历史（含已完成的工具调用与结果），
 * 因此不会重复已完成的动作，而是从断点处继续编排。</p>
 *
 * <p>注意：恢复时不追加新的用户消息（调用 {@code run()}），断点处的用户消息已包含在历史中。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class AgentCheckpointer {

	private AgentCheckpointer() {
		throw new AssertionError("No instances");
	}

	/**
	 * 为一次即将开始的运行构建初始检查点。
	 *
	 * <p>历史 = {@code baseRequest.messages + memory.history + userMessage}，
	 * 迭代数 0，最终答案 null。</p>
	 *
	 * @param sessionId   会话标识
	 * @param agent       编排器（提供 baseRequest 与可选 memory）
	 * @param userMessage 当轮用户消息（null/空白表示不追加）
	 * @return 初始检查点
	 */
	public static AgentCheckpoint create(String sessionId, ReActAgent agent, String userMessage) {
		Assert.notBlank(sessionId, "sessionId must not be blank");
		Assert.notNull(agent, "agent must not be null");
		List<ChatMessage> history = new ArrayList<>(agent.baseRequestMessages());
		if (agent.conversationMemory() != null) {
			history.addAll(agent.conversationMemory().history());
		}
		if (userMessage != null && !userMessage.isBlank()) {
			history.add(ChatMessage.user(userMessage));
		}
		return new AgentCheckpoint(sessionId, history, 0, null,
			System.currentTimeMillis(), Json.object());
	}

	/**
	 * 保存检查点。
	 *
	 * @param store     存储
	 * @param checkpoint 检查点
	 */
	public static void save(CheckpointStore store, AgentCheckpoint checkpoint) {
		Assert.notNull(store, "store must not be null");
		Assert.notNull(checkpoint, "checkpoint must not be null");
		store.save(checkpoint);
	}

	/**
	 * 加载检查点。
	 *
	 * @param store     存储
	 * @param sessionId 会话标识
	 * @return 检查点（不存在为 empty）
	 */
	public static Optional<AgentCheckpoint> load(CheckpointStore store, String sessionId) {
		Assert.notNull(store, "store must not be null");
		Assert.notBlank(sessionId, "sessionId must not be blank");
		return store.load(sessionId);
	}

	/**
	 * 从检查点恢复并继续编排。
	 *
	 * <p>把检查点历史中除 {@code baseRequest.messages} 前缀外的尾部灌入新记忆，
	 * 然后 {@code agent.withMemory(memory).run()}。</p>
	 *
	 * @param store     存储
	 * @param sessionId 会话标识
	 * @param agent     模板编排器（其 baseRequest 与 client/registry 等被复用）
	 * @return 继续编排后的最终答案
	 * @throws AiException 检查点不存在时抛出
	 */
	public static String resume(CheckpointStore store, String sessionId, ReActAgent agent) {
		Assert.notNull(store, "store must not be null");
		Assert.notBlank(sessionId, "sessionId must not be blank");
		Assert.notNull(agent, "agent must not be null");
		AgentCheckpoint checkpoint = store.load(sessionId)
			.orElseThrow(() -> new AiException("检查点不存在: " + sessionId));

		List<ChatMessage> base = agent.baseRequestMessages();
		int from = Math.min(base.size(), checkpoint.history().size());
		List<ChatMessage> tail = checkpoint.history().subList(from, checkpoint.history().size());

		InMemoryConversationMemory memory =
			new InMemoryConversationMemory(Math.max(1, tail.size() + 8));
		for (ChatMessage m : tail) {
			memory.add(m);
		}
		return agent.withMemory(memory).run();
	}
}
