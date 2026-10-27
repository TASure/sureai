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

import java.util.Map;
import java.util.Set;

/**
 * 单条内容审核结果。
 *
 * @param flagged        是否命中违规
 * @param categoryScores 类别 → 分数（0.0~1.0）
 * @param categories     命中的类别集合
 * @author sureai
 * @since 0.2.0
 */
public record ModerationResult(boolean flagged, Map<String, Double> categoryScores,
		Set<String> categories) {

	/** 性内容类别。 */
	public static final String CATEGORY_SEXUAL = "sexual";

	/** 仇恨内容类别。 */
	public static final String CATEGORY_HATE = "hate";

	/** 骚扰内容类别。 */
	public static final String CATEGORY_HARASSMENT = "harassment";

	/** 自残内容类别。 */
	public static final String CATEGORY_SELF_HARM = "self-harm";

	/** 暴力内容类别。 */
	public static final String CATEGORY_VIOLENCE = "violence";

	/**
	 * 紧凑构造器（防御性拷贝）。
	 */
	public ModerationResult {
		categoryScores = categoryScores == null ? Map.of() : Map.copyOf(categoryScores);
		categories = categories == null ? Set.of() : Set.copyOf(categories);
	}

	/**
	 * 静态工厂。
	 *
	 * @param flagged        是否命中
	 * @param categoryScores 类别分数
	 * @param categories     命中类别
	 * @return 结果
	 */
	public static ModerationResult of(boolean flagged, Map<String, Double> categoryScores,
			Set<String> categories) {
		return new ModerationResult(flagged, categoryScores, categories);
	}
}
