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
 * 拼接式结果聚合器：用分隔符把所有非空结果串起来。
 *
 * <p>默认分隔符 {@code "\n---\n"}，空列表或全空结果返回空串。</p>
 *
 * @author sureai
 * @since 1.1.0
 */
public final class ConcatenatingAggregator implements ResultAggregator {

	/** 默认分隔符。 */
	public static final String DEFAULT_DELIMITER = "\n---\n";

	private final String delimiter;

	/**
	 * 默认构造：使用 {@link #DEFAULT_DELIMITER}。
	 */
	public ConcatenatingAggregator() {
		this(DEFAULT_DELIMITER);
	}

	/**
	 * 指定分隔符构造。
	 *
	 * @param delimiter 拼接分隔符
	 */
	public ConcatenatingAggregator(String delimiter) {
		this.delimiter = delimiter == null ? "" : delimiter;
	}

	@Override
	public String aggregate(List<String> results) {
		if (results == null || results.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (String r : results) {
			if (r == null || r.isEmpty()) {
				continue;
			}
			if (!first) {
				sb.append(this.delimiter);
			}
			sb.append(r);
			first = false;
		}
		return sb.toString();
	}
}
