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

import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;
import com.sure.ai.openai.OpenAiUtil;

/**
 * 微调（Fine-tuning）使用示例。
 *
 * <p>演示 {@code FineTuneRequest} 构造与 {@code OpenAiUtil.client().createFineTune(...)} 提交
 * 微调任务、{@code getFineTune(jobId)} 查询状态的用法。</p>
 *
 * <p><b>前置条件</b>：微调必须先通过 {@code uploadTrainingFile(fileName, content)} 上传
 * 训练集（jsonl），拿到 {@code trainingFileId} 后才能提交任务。本 Demo 从环境变量
 * {@code SURE_AI_OPENAI_TRAINING_FILE_ID} 读取已上传的文件 ID；未设置时仅打印用法说明，
 * 不真实提交任务（避免误扣费）。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class FineTuneDemo {

	private FineTuneDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		System.out.println("=== OpenAI 微调（Fine-tuning）===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}

		String trainingFileId = System.getenv("SURE_AI_OPENAI_TRAINING_FILE_ID");
		if (trainingFileId == null || trainingFileId.isBlank()) {
			System.out.println("  未设置 SURE_AI_OPENAI_TRAINING_FILE_ID，仅演示用法：");
			System.out.println("  // 1) 先上传训练集（jsonl，purpose=fine-tune）");
			System.out.println("  String fileId = OpenAiUtil.client()");
			System.out.println("      .uploadTrainingFile(\"train.jsonl\", jsonlBytes);");
			System.out.println("  // 2) 提交微调任务");
			System.out.println("  FineTuneRequest req = FineTuneRequest.builder()");
			System.out.println("      .model(\"gpt-4o-mini\")");
			System.out.println("      .trainingFileId(fileId)");
			System.out.println("      .suffix(\"my-ft\")");
			System.out.println("      .build();");
			System.out.println("  FineTuneResponse job = OpenAiUtil.client().createFineTune(req);");
			System.out.println("  // 3) 轮询 getFineTune(job.id()) 直至 isCompleted()/isFailed()");
			return;
		}

		try {
			// 1. 构造微调请求
			FineTuneRequest req = FineTuneRequest.builder()
				.model("gpt-4o-mini")
				.trainingFileId(trainingFileId)
				.suffix("sureai-demo")
				.build();

			// 2. 提交任务（实际会创建一个计费任务）
			FineTuneResponse job = OpenAiUtil.client().createFineTune(req);
			printJob("  提交结果", job);

			// 3. 查询任务状态（生产环境建议按 isCompleted/isFailed 轮询）
			FineTuneResponse latest = OpenAiUtil.client().getFineTune(job.id());
			printJob("  当前状态", latest);
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}

	private static void printJob(String label, FineTuneResponse job) {
		System.out.println(label + ": id=" + job.id()
			+ " status=" + job.status()
			+ " model=" + job.model()
			+ " fineTunedModel=" + job.fineTunedModel()
			+ " completed=" + job.isCompleted()
			+ " failed=" + job.isFailed()
			+ (job.error() != null ? " error=" + job.error() : ""));
	}
}
