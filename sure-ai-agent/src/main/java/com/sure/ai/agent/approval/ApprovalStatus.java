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
 * 审批结果状态。
 *
 * @author sureai
 * @since 1.7.0
 */
public enum ApprovalStatus {

	/** 审批通过，工具可正常执行。 */
	APPROVED,

	/** 审批被拒绝，工具不执行，拒绝原因回灌模型。 */
	REJECTED,

	/** 等待审批超时，工具跳过，超时提示回灌模型。 */
	TIMEOUT
}
