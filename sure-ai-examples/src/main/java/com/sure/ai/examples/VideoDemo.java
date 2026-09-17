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

import com.sure.ai.model.VideoResponse;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.qwen.QwenModels;
import com.sure.ai.qwen.QwenUtil;
import com.sure.ai.zhipu.ZhipuModels;
import com.sure.ai.zhipu.ZhipuUtil;

/**
 * 视频生成跨平台使用示例。
 *
 * <p>统一演示 OpenAI Sora 2、通义万相 Wan 2.6、智谱 CogVideoX 3 三个平台的文生视频调用。
 * 视频生成全平台均为异步任务模式（提交→轮询→结果），SDK 内部完成轮询，对外统一以
 * {@code XxxUtil.video(model, prompt)} 一行同步调用返回结果。每个平台独立检查对应环境变量，
 * 缺失时打印提示并优雅跳过。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class VideoDemo {

	/** 统一示例提示词。 */
	private static final String PROMPT = "一只可爱的小猫咪在阳光明媚的草地上追逐蝴蝶，慢镜头";

	private VideoDemo() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		demoOpenAi();
		demoQwen();
		demoZhipu();
	}

	/** OpenAI Sora 2（注意：Sora API 将于 2026-09-24 关闭）。 */
	private static void demoOpenAi() {
		System.out.println("=== OpenAI Sora 2 ===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			VideoResponse resp = OpenAiUtil.video(OpenAiModels.SORA_2, PROMPT);
			printResult(resp);
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}

	/** 通义万相 Wan 2.6（DashScope 异步任务，SDK 内部轮询）。 */
	private static void demoQwen() {
		System.out.println("=== 通义万相 Wan 2.6 ===");
		String apiKey = System.getenv("SURE_AI_QWEN_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_QWEN_API_KEY");
			return;
		}
		try {
			VideoResponse resp = QwenUtil.video(QwenModels.WAN2_6_T2V, PROMPT);
			printResult(resp);
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}

	/** 智谱 CogVideoX 3（异步任务，SDK 内部轮询）。 */
	private static void demoZhipu() {
		System.out.println("=== 智谱 CogVideoX 3 ===");
		String apiKey = System.getenv("SURE_AI_ZHIPU_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_ZHIPU_API_KEY");
			return;
		}
		try {
			VideoResponse resp = ZhipuUtil.video(ZhipuModels.COGVIDEOX_3, PROMPT);
			printResult(resp);
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}

	/** 打印首条视频：URL 或封面图 URL。 */
	private static void printResult(VideoResponse resp) {
		if (resp.data().isEmpty()) {
			System.out.println("  未返回视频（任务可能失败或被拒绝）");
			return;
		}
		String url = resp.firstUrl();
		if (url != null) {
			System.out.println("  视频 URL：" + url);
		}
		String cover = resp.firstCoverUrl();
		if (cover != null) {
			System.out.println("  封面图 URL：" + cover);
		}
		if (url == null && cover == null) {
			System.out.println("  结果既无视频 URL 也无封面图 URL");
		}
	}
}
