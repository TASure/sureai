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

package com.sure.ai.rag.graph;

import java.util.Collections;
import java.util.Map;

/**
 * 知识图谱关系（有向边）。
 *
 * <p>不可变记录；{@code sourceId}/{@code targetId} 均指向 {@link GraphEntity#id()}；
 * {@code label} 为关系类型（如「任职于」「位于」）；{@code metadata} 记录来源文档 id 等。
 * 关系在存储层为有向边，但社区发现等算法会按无向图处理。</p>
 *
 * @param sourceId 起点实体 id
 * @param targetId 终点实体 id
 * @param label    关系类型
 * @param metadata 元数据（含来源文档 id），不可为 null
 * @author sureai
 * @since 1.8.0
 */
public record GraphRelation(String sourceId, String targetId, String label,
		Map<String, String> metadata) {

	/**
	 * 紧凑构造器：将 null 元数据规范化为不可变空 Map。
	 *
	 * @param sourceId 起点实体 id
	 * @param targetId 终点实体 id
	 * @param label    关系类型
	 * @param metadata 元数据
	 */
	public GraphRelation {
		if (metadata == null) {
			metadata = Collections.emptyMap();
		}
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
