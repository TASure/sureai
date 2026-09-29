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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.sure.tool.lang.Assert;

/**
 * 基于 {@link ConcurrentHashMap} 的内存检查点存储。
 *
 * <p>进程内有效，重启即失；适用于测试与短期会话。线程安全。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

	private final Map<String, AgentCheckpoint> store = new ConcurrentHashMap<>();

	@Override
	public void save(AgentCheckpoint checkpoint) {
		Assert.notNull(checkpoint, "checkpoint must not be null");
		this.store.put(checkpoint.sessionId(), checkpoint);
	}

	@Override
	public Optional<AgentCheckpoint> load(String sessionId) {
		Assert.notBlank(sessionId, "sessionId must not be blank");
		return Optional.ofNullable(this.store.get(sessionId));
	}

	@Override
	public void delete(String sessionId) {
		Assert.notBlank(sessionId, "sessionId must not be blank");
		this.store.remove(sessionId);
	}

	@Override
	public List<String> listSessions() {
		return this.store.keySet().stream().sorted().toList();
	}
}
