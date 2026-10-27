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

package com.sure.ai.agent.approval;

import com.sure.ai.internal.json.JsonObject;

/**
 * 对所有工具调用都要求审批的策略。
 *
 * @author sureai
 * @since 1.7.0
 */
public final class AllToolsApprovalPolicy implements ApprovalPolicy {

	/** 单例。 */
	private static final AllToolsApprovalPolicy INSTANCE = new AllToolsApprovalPolicy();

	/** 私有构造。 */
	private AllToolsApprovalPolicy() {
	}

	/**
	 * 单例访问。
	 *
	 * @return 实例
	 */
	public static AllToolsApprovalPolicy instance() {
		return INSTANCE;
	}

	@Override
	public boolean requiresApproval(String toolName, JsonObject args) {
		return true;
	}
}
