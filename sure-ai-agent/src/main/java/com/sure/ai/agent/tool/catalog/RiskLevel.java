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

package com.sure.ai.agent.tool.catalog;

/**
 * 工具风险级别：仅作为工具市场的静态标注，供发现、筛选与上层审批策略（如
 * {@code HighRiskApprovalPolicy}）参考，本枚举本身不执行审批拦截。
 *
 * <p>级别从低到高：{@link #LOW} 只读无副作用；{@link #MEDIUM} 本地可写/外部只读；
 * {@link #HIGH} 涉及资金、删除、远程写操作；{@link #CRITICAL} 可执行命令、
 * 系统破坏或不可逆操作。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public enum RiskLevel {

	/** 低风险：纯只读 / 纯计算，无外部副作用。 */
	LOW,

	/** 中风险：本地可写或访问外部只读资源。 */
	MEDIUM,

	/** 高风险：涉及资金、删除、远程写操作。 */
	HIGH,

	/** 极高风险：命令执行、系统破坏或不可逆操作。 */
	CRITICAL
}
