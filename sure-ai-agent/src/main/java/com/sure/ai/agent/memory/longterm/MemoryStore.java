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

import java.util.List;
import java.util.Optional;

/**
 * 长期记忆底层存储：键值式 CRUD。
 *
 * <p>键值语义由 {@link #get(String)} 承担——以条目 {@code id} 为主键读写。
 * 向量检索能力见 {@link VectorMemoryStore} 子接口。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public interface MemoryStore {

	/**
	 * 写入/覆盖一条记忆。
	 *
	 * @param entry 记忆条目（非 null）
	 */
	void put(MemoryEntry entry);

	/**
	 * 按 ID 读取。
	 *
	 * @param id 条目 ID
	 * @return 条目（不存在时 empty）
	 */
	Optional<MemoryEntry> get(String id);

	/**
	 * 按 ID 删除。
	 *
	 * @param id 条目 ID
	 */
	void delete(String id);

	/**
	 * 全部条目（时间序不做保证，由实现决定）。
	 *
	 * @return 条目列表快照
	 */
	List<MemoryEntry> all();

	/**
	 * 清空全部记忆。
	 */
	void clear();
}
