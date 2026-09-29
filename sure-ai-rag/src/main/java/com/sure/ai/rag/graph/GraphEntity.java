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
 * 知识图谱实体（节点）。
 *
 * <p>不可变记录；{@code id} 为规范化后的实体名（见
 * {@link KnowledgeGraph#normalizeName(String)}），用于跨文档同义实体合并；
 * {@code name} 为首次出现时的展示名；{@code type} 为实体类别（人/组织/地点/概念…，
 * 可空）；{@code metadata} 记录来源文档 id 等附加信息。</p>
 *
 * @param id       规范化实体 id（与实体名一一对应）
 * @param name     实体展示名
 * @param type     实体类别，可空
 * @param metadata 元数据（含来源文档 id），不可为 null
 * @author sureai
 * @since 1.8.0
 */
public record GraphEntity(String id, String name, String type, Map<String, String> metadata) {

	/**
	 * 紧凑构造器：将 null 元数据规范化为不可变空 Map。
	 *
	 * @param id       规范化实体 id
	 * @param name     实体展示名
	 * @param type     实体类别
	 * @param metadata 元数据
	 */
	public GraphEntity {
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
