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

package com.sure.ai.examples;

import java.util.Map;

import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;
import com.sure.ai.model.ModerationResult;
import com.sure.ai.openai.OpenAiUtil;

/**
 * 内容审核（Moderation）使用示例。
 *
 * <p>演示 {@code OpenAiUtil.client().moderate(ModerationRequest.of(...))} 对文本做合规审核，
 * 打印是否 flagged 及各类别分数。类别常量见 {@link ModerationResult}。</p>
 *
 * <p>检查环境变量 {@code SURE_AI_OPENAI_API_KEY}，缺失时打印提示并优雅跳过。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class ModerationDemo {

	/** 待审核文本。 */
	private static final String INPUT = "今天天气真不错，适合出门散步。";

	private ModerationDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== OpenAI 内容审核（Moderation）===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			ModerationResponse resp = OpenAiUtil.client()
				.moderate(ModerationRequest.of(INPUT));

			System.out.println("  输入：" + INPUT);
			System.out.println("  flagged：" + resp.flagged());

			int idx = 1;
			for (ModerationResult r : resp.results()) {
				System.out.println("  结果 #" + idx++ + " flagged=" + r.flagged()
					+ " 命中类别=" + r.categories());
				System.out.println("  类别分数：");
				for (Map.Entry<String, Double> e : r.categoryScores().entrySet()) {
					System.out.printf("    %-12s %.4f%n", e.getKey(), e.getValue());
				}
			}
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
