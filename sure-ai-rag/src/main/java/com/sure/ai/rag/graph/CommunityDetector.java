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

import java.util.List;

/**
 * 社区发现器：把知识图谱划分为若干社区（紧密连接的实体簇）。
 *
 * @author sureai
 * @since 1.8.0
 */
public interface CommunityDetector {

	/**
	 * 对知识图谱执行社区划分。
	 *
	 * @param graph 知识图谱，不可为 null
	 * @return 社区列表（社区编号从 0 开始），不会返回 null
	 */
	List<GraphCommunity> detect(KnowledgeGraph graph);
}
