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

package com.sure.ai.model;

import java.util.List;

/**
 * 内容审核响应。
 *
 * @param id     审核请求 ID，可能为 null
 * @param model  审核模型名
 * @param results 审核结果列表
 * @param rawJson 原始 JSON
 * @author sureai
 * @since 0.2.0
 */
public record ModerationResponse(String id, String model, List<ModerationResult> results,
		String rawJson) {

	/**
	 * 紧凑构造器（防御性拷贝）。
	 */
	public ModerationResponse {
		results = results == null ? List.of() : List.copyOf(results);
	}

	/**
	 * 静态工厂。
	 *
	 * @param id      ID
	 * @param model   模型名
	 * @param results 结果列表
	 * @param rawJson 原始 JSON
	 * @return 响应
	 */
	public static ModerationResponse of(String id, String model, List<ModerationResult> results,
			String rawJson) {
		return new ModerationResponse(id, model, results, rawJson);
	}

	/**
	 * 是否任意一条命中违规。
	 *
	 * @return 任一结果 flagged 返回 true
	 */
	public boolean flagged() {
		for (ModerationResult r : this.results) {
			if (r.flagged()) {
				return true;
			}
		}
		return false;
	}
}
