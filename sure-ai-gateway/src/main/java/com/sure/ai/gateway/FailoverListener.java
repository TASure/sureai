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

package com.sure.ai.gateway;

import java.util.List;

/**
 * 故障转移事件监听器。
 *
 * <p>所有回调在调用线程内同步触发；实现应轻量、不抛异常，以免干扰主流程。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public interface FailoverListener {

	/**
	 * 一次失败转移：{@code from} 失败后改用 {@code to}。
	 *
	 * @param fromPlatform   失败实例的平台
	 * @param fromInstanceId 失败实例 ID
	 * @param toPlatform     下一候选的平台
	 * @param toInstanceId   下一候选 ID
	 * @param cause          失败原因
	 * @param attempt        当前是第几次尝试（从 1 开始）
	 */
	void onFailover(String fromPlatform, String fromInstanceId,
			String toPlatform, String toInstanceId, Exception cause, int attempt);

	/**
	 * 所有候选均失败或达到最大尝试次数。
	 *
	 * @param tried     本次尝试过的候选
	 * @param lastCause 最后一次失败原因
	 */
	void onExhausted(List<ClientCandidate> tried, Exception lastCause);
}
