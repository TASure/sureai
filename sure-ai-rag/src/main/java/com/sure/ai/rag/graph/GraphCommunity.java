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
import java.util.List;

/**
 * 图社区：一组相互紧密连接的实体。
 *
 * @param communityId 社区编号（从 0 开始）
 * @param entityIds   社区内实体 id（规范化实体 id）
 * @param summary     社区主题摘要，可空（社区发现阶段通常为空，由摘要器填充）
 * @author sureai
 * @since 1.8.0
 */
public record GraphCommunity(int communityId, List<String> entityIds, String summary) {

	/**
	 * 紧凑构造器：防御性拷贝实体列表。
	 *
	 * @param communityId 社区编号
	 * @param entityIds   实体 id 列表
	 * @param summary     社区摘要
	 */
	public GraphCommunity {
		entityIds = entityIds == null ? List.of() : List.copyOf(entityIds);
	}

	/**
	 * 返回不可变的实体 id 列表。
	 *
	 * @return 实体 id 列表
	 */
	@Override
	public List<String> entityIds() {
		return Collections.unmodifiableList(entityIds);
	}
}
