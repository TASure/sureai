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

import java.util.Scanner;

/**
 * 控制台审批处理器：把审批请求打印到标准输出，并从标准输入读取 y/n。
 *
 * <p>输入 {@code y/yes/是} 视为通过，其余视为拒绝。本实现会阻塞等待用户输入，
 * 生产环境若需要超时请用 {@link TimeoutApprovalHandler} 包装；测试不覆盖本类。</p>
 *
 * @author sureai
 * @since 1.7.0
 */
public final class ConsoleApprovalHandler implements ApprovalHandler {

	/** 输入扫描器。 */
	private final Scanner scanner = new Scanner(System.in, "UTF-8");

	@Override
	public ApprovalDecision request(ApprovalRequest request) {
		System.out.println("==========================================================");
		System.out.println("[审批请求] " + request.requestId());
		System.out.println("  工具: " + request.toolName());
		if (request.description() != null) {
			System.out.println("  动作: " + request.description());
		}
		System.out.println("  高危: " + (request.highRisk() ? "是" : "否"));
		System.out.print("批准执行? (y/n): ");
		String line = this.scanner.hasNextLine() ? this.scanner.nextLine().trim() : "";
		if ("y".equalsIgnoreCase(line) || "yes".equalsIgnoreCase(line) || "是".equals(line)) {
			return ApprovalDecision.approved("console approved");
		}
		return ApprovalDecision.rejected("console rejected: " + line);
	}

	@Override
	public String name() {
		return "console";
	}
}
