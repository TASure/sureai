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

package com.sure.ai.agent.orchestrator;

import java.util.List;

/**
 * 结果聚合器：把多个子 Agent 的输出合并成最终答案。
 *
 * @author sureai
 * @since 1.1.0
 */
@FunctionalInterface
public interface ResultAggregator {

	/**
	 * 聚合子任务结果。
	 *
	 * @param results 子任务结果列表（与拆分顺序一致；失败项为 {@code "[ERROR: ...]"} 文本）
	 * @return 最终答案文本
	 */
	String aggregate(List<String> results);
}
