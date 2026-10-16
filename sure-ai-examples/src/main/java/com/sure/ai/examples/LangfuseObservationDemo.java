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

import com.sure.ai.client.AiConfig;
import com.sure.ai.deepseek.DeepSeekClient;
import com.sure.ai.deepseek.DeepSeekModels;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.otel.langfuse.LangfuseExporters;

/**
 * Langfuse 原生导出最小示例：把一次聊天调用以 trace + generation 上报到 Langfuse。
 *
 * <p>前置环境变量：</p>
 * <ul>
 *   <li>{@code LANGFUSE_PUBLIC_KEY} / {@code LANGFUSE_SECRET_KEY}（必填，缺省则导出器空操作、跳过）；</li>
 *   <li>{@code LANGFUSE_HOST}（可选，默认 https://cloud.langfuse.com）；</li>
 *   <li>{@code SURE_AI_DEEPSEEK_API_KEY}（演示用模型提供方密钥）。</li>
 * </ul>
 *
 * <p>未配置 Langfuse 密钥时，导出器为空操作，不影响正常对话。</p>
 *
 * @author sureai
 * @since 2.4.0
 */
public final class LangfuseObservationDemo {

	private LangfuseObservationDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		String pk = System.getenv("LANGFUSE_PUBLIC_KEY");
		String sk = System.getenv("LANGFUSE_SECRET_KEY");
		if (pk == null || pk.isBlank() || sk == null || sk.isBlank()) {
			System.out.println("未配置 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY，"
				+ "导出器为空操作，本示例跳过（不影响主流程）。");
			return;
		}
		String apiKey = System.getenv("SURE_AI_DEEPSEEK_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("请设置 SURE_AI_DEEPSEEK_API_KEY 后再运行本示例。");
			return;
		}
		try {
			// 关键一行：从环境变量构建 Langfuse 导出器并挂到 client
			AiConfig config = AiConfig.builder()
				.apiKey(apiKey)
				.metricsCollector(LangfuseExporters.metricsCollectorFromEnv())
				.build();
			DeepSeekClient client = new DeepSeekClient(config);
			ChatResponse resp = client.chat(DeepSeekModels.DEEPSEEK_CHAT,
				"用一句话回答：1+1等于几？");
			System.out.println("模型回复：" + resp.firstText());
			System.out.println("该次调用已作为 trace+generation 上报 Langfuse，"
				+ "可在项目 Trace 页查看耗时与 token 用量。");
			client.close();
		} catch (Exception e) {
			System.out.println("Langfuse 示例执行异常：" + e.getMessage());
		}
	}
}
