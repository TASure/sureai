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
 * 委派深度超限异常。
 *
 * <p>当当前委派链长度达到配置的最大深度（默认 5）仍继续向下委派时抛出，
 * 防止无界递归委派把栈 / 预算耗尽。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public class DelegationDepthExceededException extends DelegationException {

	private static final long serialVersionUID = 1L;

	/**
	 * 构造异常。
	 *
	 * @param message 异常信息（含当前深度与上限）
	 */
	public DelegationDepthExceededException(String message) {
		super(message);
	}
}
