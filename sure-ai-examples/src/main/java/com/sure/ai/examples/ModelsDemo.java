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

import java.util.List;

import com.sure.ai.model.Model;
import com.sure.ai.openai.OpenAiUtil;

/**
 * 模型列表（Models）使用示例。
 *
 * <p>演示 {@code OpenAiUtil.client().listModels()} 拉取当前账号可用模型列表，
 * 打印模型 ID、所有者与创建时间。</p>
 *
 * <p>检查环境变量 {@code SURE_AI_OPENAI_API_KEY}，缺失时打印提示并优雅跳过。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class ModelsDemo {

	/** 最多打印的模型条数，避免输出过长。 */
	private static final int MAX_PRINT = 20;

	private ModelsDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== OpenAI 模型列表（Models）===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			List<Model> models = OpenAiUtil.client().listModels();
			System.out.println("  共 " + models.size() + " 个模型（最多打印前 " + MAX_PRINT + " 条）：");
			int shown = Math.min(models.size(), MAX_PRINT);
			for (int i = 0; i < shown; i++) {
				Model m = models.get(i);
				System.out.printf("    %-32s  owned_by=%s  created=%s%n",
					m.id(), m.ownedBy(), m.created());
			}
			if (models.size() > MAX_PRINT) {
				System.out.println("    …（其余 " + (models.size() - MAX_PRINT) + " 条省略）");
			}
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
