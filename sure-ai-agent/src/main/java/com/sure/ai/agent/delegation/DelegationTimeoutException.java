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

/**
 * 委派执行超时异常。
 *
 * <p>当为单次委派配置了执行上限（{@code timeout}），子 Agent 在预算内未返回时抛出；
 * 内部会中断取消正在执行的子任务线程。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public class DelegationTimeoutException extends DelegationException {

	private static final long serialVersionUID = 1L;

	/**
	 * 构造超时异常。
	 *
	 * @param message 异常信息（含 agentId 与超时预算）
	 */
	public DelegationTimeoutException(String message) {
		super(message);
	}
}
