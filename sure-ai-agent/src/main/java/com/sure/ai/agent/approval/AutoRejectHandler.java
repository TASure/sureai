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

/**
 * 自动拒绝审批处理器：立即返回 {@link ApprovalStatus#REJECTED}。
 *
 * <p>仅用于测试拒绝路径。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class AutoRejectHandler implements ApprovalHandler {

	/** 单例。 */
	private static final AutoRejectHandler INSTANCE = new AutoRejectHandler();

	/** 拒绝原因。 */
	private final String reason;

	/**
	 * 私有构造（默认原因）。
	 */
	private AutoRejectHandler() {
		this("auto-rejected");
	}

	/**
	 * 私有构造。
	 *
	 * @param reason 拒绝原因
	 */
	private AutoRejectHandler(String reason) {
		this.reason = reason;
	}

	/**
	 * 单例访问。
	 *
	 * @return 实例
	 */
	public static AutoRejectHandler instance() {
		return INSTANCE;
	}

	/**
	 * 带自定义原因的实例。
	 *
	 * @param reason 拒绝原因
	 * @return 实例
	 */
	public static AutoRejectHandler withReason(String reason) {
		return new AutoRejectHandler(reason);
	}

	@Override
	public ApprovalDecision request(ApprovalRequest request) {
		return ApprovalDecision.rejected(this.reason);
	}

	@Override
	public String name() {
		return "auto-reject";
	}
}
