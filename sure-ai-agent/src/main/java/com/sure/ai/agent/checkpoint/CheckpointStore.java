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

import java.util.List;
import java.util.Optional;

/**
 * 检查点存储 SPI：按会话标识保存/加载/删除 {@link AgentCheckpoint}。
 *
 * <p>内置实现：{@link InMemoryCheckpointStore}（并发 Map）与
 * {@link FileCheckpointStore}（本地目录，每会话一个 JSON 文件）。
 * 用户可自行扩展为数据库 / Redis 实现。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public interface CheckpointStore {

	/**
	 * 保存（覆盖）指定会话的检查点。
	 *
	 * @param checkpoint 检查点（非 null）
	 */
	void save(AgentCheckpoint checkpoint);

	/**
	 * 加载指定会话的检查点。
	 *
	 * @param sessionId 会话标识
	 * @return 检查点，不存在返回 empty
	 */
	Optional<AgentCheckpoint> load(String sessionId);

	/**
	 * 删除指定会话的检查点（不存在时静默忽略）。
	 *
	 * @param sessionId 会话标识
	 */
	void delete(String sessionId);

	/**
	 * 列出全部会话标识（顺序由实现决定，通常排序）。
	 *
	 * @return 会话标识列表快照
	 */
	List<String> listSessions();
}
