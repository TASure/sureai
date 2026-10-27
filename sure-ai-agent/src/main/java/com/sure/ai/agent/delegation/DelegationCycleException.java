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

package com.sure.ai.agent.delegation;

import java.util.List;

/**
 * 委派环检测异常。
 *
 * <p>同一调用链上重复委派到同一个 Agent（按 {@code agentId} 判定）时抛出，
 * 覆盖直接环（A → A）与间接环（A → B → C → A）。例如子 Agent 的工具集里
 * 又把自己（或祖先）包装成工具，会形成无限来回委派。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public class DelegationCycleException extends DelegationException {

	private static final long serialVersionUID = 1L;

	/** 已在调用链上的 Agent id 快照（自根到当前父）。 */
	private final List<String> chain;

	/**
	 * 构造异常。
	 *
	 * @param message 异常信息（含重复的 agentId）
	 * @param chain   触发环时已在链上的 Agent id 不可变快照
	 */
	public DelegationCycleException(String message, List<String> chain) {
		super(message);
		this.chain = List.copyOf(chain);
	}

	/**
	 * 触发环时已在调用链上的 Agent id 快照（自根到当前父）。
	 *
	 * @return 不可变 id 列表
	 */
	public List<String> chain() {
		return this.chain;
	}
}
