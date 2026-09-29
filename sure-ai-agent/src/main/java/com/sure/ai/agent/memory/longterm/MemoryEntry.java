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

package com.sure.ai.agent.memory.longterm;

import java.util.Map;
import java.util.UUID;

/**
 * 一条长期记忆条目。
 *
 * <p>不可变；{@code metadata} 为不可修改快照，{@code embedding} 为防御性拷贝。
 * 无向量化能力时 {@code embedding} 可为 null，检索退化为文本匹配。</p>
 *
 * @param id                条目唯一标识（UUID）
 * @param content           记忆正文（自然语言陈述）
 * @param metadata          元数据（如 sessionId / role / source），不可修改快照
 * @param createdAtEpochMs  创建时间戳（毫秒）
 * @param embedding         向量（可 null；存在时用于余弦相似度检索）
 * @author sureai
 * @since 1.7.0
 */
public record MemoryEntry(String id, String content, Map<String, String> metadata,
		long createdAtEpochMs, float[] embedding) {

	/**
	 * 紧凑构造器：防御性拷贝。
	 *
	 * @param id                条目 ID
	 * @param content           正文
	 * @param metadata          元数据
	 * @param createdAtEpochMs  创建时间戳
	 * @param embedding         向量
	 */
	public MemoryEntry {
		if (id == null || id.isBlank()) {
			id = UUID.randomUUID().toString();
		}
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		if (embedding != null) {
			embedding = embedding.clone();
		}
	}

	/**
	 * 静态工厂：自动生成 id 与当前时间戳。
	 *
	 * @param content   正文
	 * @param metadata  元数据（可空）
	 * @return 条目
	 */
	public static MemoryEntry of(String content, Map<String, String> metadata) {
		return new MemoryEntry(UUID.randomUUID().toString(), content, metadata,
			System.currentTimeMillis(), null);
	}

	/**
	 * 返回一个携带新向量的副本（正文/元数据不变）。
	 *
	 * @param embedding 新向量（可 null）
	 * @return 新条目
	 */
	public MemoryEntry withEmbedding(float[] embedding) {
		return new MemoryEntry(this.id, this.content, this.metadata,
			this.createdAtEpochMs, embedding);
	}

	@Override
	public float[] embedding() {
		return this.embedding == null ? null : this.embedding.clone();
	}
}
