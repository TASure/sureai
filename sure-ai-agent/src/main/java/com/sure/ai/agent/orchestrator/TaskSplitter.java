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

package com.sure.ai.agent.orchestrator;

import java.util.List;

/**
 * 任务拆分器：把一个大任务拆成可并行执行的子任务列表。
 *
 * <p>多 Agent 编排的第一步：由 {@code AgentOrchestrator} 调用，
 * 每个子任务交给一个独立的 {@code ReActAgent} 实例执行。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
@FunctionalInterface
public interface TaskSplitter {

	/**
	 * 把原始任务拆成子任务列表。
	 *
	 * @param task 原始任务文本
	 * @return 子任务列表（可能为空，不为 null）
	 */
	List<String> split(String task);
}
