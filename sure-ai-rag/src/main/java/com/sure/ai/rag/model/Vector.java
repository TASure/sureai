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

package com.sure.ai.rag.model;

import java.util.Collections;
import java.util.Map;

/**
 * 向量条目：文本向量化后的存储单元。
 *
 * @param id 向量唯一标识（与来源文档分块对应）
 * @param embedding 嵌入向量（float 数组）
 * @param text 原始文本（检索命中后直接作为上下文）
 * @param metadata 元数据
 * @author sureai
 * @since 0.2.0
 */
public record Vector(String id, float[] embedding, String text, Map<String, String> metadata) {

	/**
	 * 创建向量条目。
	 *
	 * @param id 向量唯一标识
	 * @param embedding 嵌入向量
	 * @param text 原始文本
	 * @return 向量条目（无元数据）
	 */
	public static Vector of(String id, float[] embedding, String text) {
		return new Vector(id, embedding, text, Collections.emptyMap());
	}

	/**
	 * 创建带元数据的向量条目。
	 *
	 * @param id 向量唯一标识
	 * @param embedding 嵌入向量
	 * @param text 原始文本
	 * @param metadata 元数据
	 * @return 向量条目
	 */
	public static Vector of(String id, float[] embedding, String text,
			Map<String, String> metadata) {
		return new Vector(id, embedding, text, metadata);
	}

	/**
	 * 紧凑构造器：元数据为 null 时规范化为空 Map，并复制嵌入向量防止外部篡改。
	 *
	 * @param id 向量唯一标识
	 * @param embedding 嵌入向量
	 * @param text 原始文本
	 * @param metadata 元数据
	 */
	public Vector {
		if (metadata == null) {
			metadata = Collections.emptyMap();
		}
		if (embedding != null) {
			embedding = embedding.clone();
		}
	}

	/**
	 * 返回嵌入向量的防御性副本。
	 *
	 * @return 嵌入向量副本
	 */
	@Override
	public float[] embedding() {
		return embedding == null ? null : embedding.clone();
	}

	/**
	 * 返回不可变的元数据视图。
	 *
	 * @return 元数据
	 */
	@Override
	public Map<String, String> metadata() {
		return Collections.unmodifiableMap(metadata);
	}
}
