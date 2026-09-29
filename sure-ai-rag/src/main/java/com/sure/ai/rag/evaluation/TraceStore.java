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

package com.sure.ai.rag.evaluation;

import java.util.List;

/**
 * 轨迹存储抽象：保存、列出与读取历史 RAG 轨迹，供离线回放与回归评估。
 *
 * <p>内置 {@link InMemoryTraceStore} 进程内实现；生产环境可持久化扩展到数据库或对象存储。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public interface TraceStore {

	/**
	 * 保存一条轨迹（同 traceId 覆盖旧值）。
	 *
	 * @param trace 轨迹
	 */
	void save(RagTrace trace);

	/**
	 * 列出全部轨迹（按保存顺序）。
	 *
	 * @return 轨迹列表的不可变副本
	 */
	List<RagTrace> all();

	/**
	 * 按 traceId 读取轨迹。
	 *
	 * @param traceId 轨迹 ID
	 * @return 轨迹，不存在返回 {@code null}
	 */
	RagTrace findById(String traceId);

	/**
	 * 轨迹数量。
	 *
	 * @return 数量
	 */
	int size();
}
