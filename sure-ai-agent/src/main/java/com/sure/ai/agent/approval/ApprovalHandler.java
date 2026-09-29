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
 * 审批处理器：接收一个审批请求并阻塞等待最终决定。
 *
 * <p>典型实现：控制台 y/n 交互、工单系统轮询、消息推送回调、自动化策略脚本。
 * 超时控制可由实现自行负责（如 {@link TimeoutApprovalHandler} 包装），
 * 编排器侧不额外设置阻塞超时。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public interface ApprovalHandler {

	/**
	 * 发起一次审批请求并阻塞等待决定。
	 *
	 * @param request 审批请求
	 * @return 审批决定（永不返回 null）
	 */
	ApprovalDecision request(ApprovalRequest request);

	/**
	 * 处理器名称（如 "console" / "auto" / "ticket"），供日志与排查使用。
	 *
	 * @return 名称
	 */
	String name();
}
