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

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;

/**
 * Agent 会话检查点：某一时刻编排状态的可序列化快照。
 *
 * <p>包含：会话标识、已完成的对话历史（{@code baseRequest.messages + 记忆 + 当轮用户消息
 * + 已执行的工具调用/结果}）、已完成迭代数、完成后的最终答案（进行中为 {@code null}）、
 * 创建时间戳，以及供扩展使用的自定义元数据 {@link JsonObject}。</p>
 *
 * <p>不可变；{@link #history()} 为不可变快照。通过 {@link #builder()} 构建，
 * 通过 {@link CheckpointSerializer} 与 JSON 互转。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public record AgentCheckpoint(String sessionId, List<ChatMessage> history, int iteration,
		String finalAnswer, long createdAtEpochMs, JsonObject metadata) {

	/**
	 * 紧凑构造器：防御性拷贝历史，元数据缺省为空对象。
	 *
	 * @param sessionId       会话标识（非空）
	 * @param history         对话历史（null 视为空）
	 * @param iteration       已完成迭代数
	 * @param finalAnswer     最终答案（进行中为 null）
	 * @param createdAtEpochMs 创建时间戳（毫秒）
	 * @param metadata        自定义元数据（null 视为空对象）
	 */
	public AgentCheckpoint {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId must not be blank");
		}
		history = history == null ? List.of() : List.copyOf(history);
		metadata = metadata == null ? Json.object() : metadata;
	}

	/**
	 * 创建构建器。
	 *
	 * @return 构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * {@link AgentCheckpoint} 可变构建器。
	 */
	public static final class Builder {

		private String sessionId;
		private List<ChatMessage> history = List.of();
		private int iteration;
		private String finalAnswer;
		private long createdAtEpochMs = System.currentTimeMillis();
		private JsonObject metadata;

		/**
		 * 设置会话标识。
		 *
		 * @param sessionId 会话标识
		 * @return this
		 */
		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		/**
		 * 设置对话历史。
		 *
		 * @param history 历史
		 * @return this
		 */
		public Builder history(List<ChatMessage> history) {
			this.history = history == null ? new ArrayList<>() : new ArrayList<>(history);
			return this;
		}

		/**
		 * 设置已完成迭代数。
		 *
		 * @param iteration 迭代数
		 * @return this
		 */
		public Builder iteration(int iteration) {
			this.iteration = iteration;
			return this;
		}

		/**
		 * 设置最终答案。
		 *
		 * @param finalAnswer 答案（进行中传 null）
		 * @return this
		 */
		public Builder finalAnswer(String finalAnswer) {
			this.finalAnswer = finalAnswer;
			return this;
		}

		/**
		 * 设置创建时间戳。
		 *
		 * @param createdAtEpochMs 毫秒时间戳
		 * @return this
		 */
		public Builder createdAtEpochMs(long createdAtEpochMs) {
			this.createdAtEpochMs = createdAtEpochMs;
			return this;
		}

		/**
		 * 设置自定义元数据。
		 *
		 * @param metadata 元数据
		 * @return this
		 */
		public Builder metadata(JsonObject metadata) {
			this.metadata = metadata;
			return this;
		}

		/**
		 * 构建检查点。
		 *
		 * @return 检查点
		 */
		public AgentCheckpoint build() {
			return new AgentCheckpoint(this.sessionId, this.history, this.iteration,
				this.finalAnswer, this.createdAtEpochMs, this.metadata);
		}
	}
}
