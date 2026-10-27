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

import com.sure.ai.model.BatchRequest;
import com.sure.ai.model.BatchResponse;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;

/**
 * 批处理（Batches）使用示例。
 *
 * <p>演示 OpenAI Batches 异步任务：提交任务（{@code OpenAiUtil.batch}）后按 id 查询状态
 * （{@code OpenAiUtil.getBatch}）。</p>
 *
 * <p><b>前置条件</b>：Batches 以一个已上传的 JSONL 输入文件为输入，须先通过 OpenAI 文件上传
 * 接口取得 {@code input_file_id}，再通过环境变量 {@code SURE_AI_OPENAI_BATCH_INPUT_FILE_ID}
 * 注入。未设置该环境变量时仅打印使用说明并跳过，不发起真实请求。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class BatchDemo {

	/** 环境变量名：已上传 JSONL 输入文件的 file id。 */
	private static final String ENV_INPUT_FILE_ID = "SURE_AI_OPENAI_BATCH_INPUT_FILE_ID";

	private BatchDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== OpenAI Batches 批处理 ===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		String inputFileId = System.getenv(ENV_INPUT_FILE_ID);
		if (inputFileId == null || inputFileId.isBlank()) {
			System.out.println("  跳过：未设置 " + ENV_INPUT_FILE_ID);
			System.out.println("  使用说明：");
			System.out.println("    1. 准备 JSONL 文件，每行一个任务：");
			System.out.println("       {\"custom_id\":\"req-1\",\"method\":\"POST\",\"url\":\"/v1/chat/completions\",");
			System.out.println("        \"body\":{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"...\"}]}}");
			System.out.println("    2. 通过 OpenAI 文件上传接口上传该 JSONL，取得 input_file_id；");
			System.out.println("    3. export " + ENV_INPUT_FILE_ID + "=batch_xxx_file_id 后重新运行本示例。");
			return;
		}
		try {
			// 1. 提交批处理任务
			BatchRequest request = BatchRequest.builder()
				.model(OpenAiModels.GPT_4O_MINI)
				.inputFileId(inputFileId)
				.completionWindow("24h")
				.build();
			BatchResponse created = OpenAiUtil.batch(request);
			System.out.println("  已提交任务，id=" + created.id() + "，初始状态=" + created.status());

			// 2. 按 id 查询一次状态（生产环境建议轮询直至 isCompleted()/isFailed()）
			BatchResponse latest = OpenAiUtil.getBatch(created.id());
			BatchResponse.RequestCounts counts = latest.requestCounts();
			System.out.println("  当前状态=" + latest.status()
				+ "，total=" + counts.total()
				+ "，completed=" + counts.completed()
				+ "，failed=" + counts.failed()
				+ (latest.error() == null ? "" : ("，error=" + latest.error())));
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}
}
