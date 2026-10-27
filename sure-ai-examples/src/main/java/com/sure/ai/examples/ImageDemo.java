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

import com.sure.ai.model.ImageResponse;
import com.sure.ai.openai.OpenAiModels;
import com.sure.ai.openai.OpenAiUtil;
import com.sure.ai.qwen.QwenModels;
import com.sure.ai.qwen.QwenUtil;
import com.sure.ai.zhipu.ZhipuModels;
import com.sure.ai.zhipu.ZhipuUtil;

/**
 * 图像生成跨平台使用示例。
 *
 * <p>统一演示 OpenAI DALL·E 3、通义万相 wanx-v1、智谱 cogview-3 三个平台的文生图调用。
 * 每个平台独立检查对应环境变量，缺失时打印提示并优雅跳过，不抛异常中断整个 demo；
 * 对外屏蔽各平台同步（OpenAI / 智谱）与异步轮询（通义万相）的差异，均以
 * {@code XxxUtil.image(model, prompt)} 一行同步调用返回结果。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class ImageDemo {

	/** 统一示例提示词。 */
	private static final String PROMPT = "一只可爱的小猫咪坐在阳光明媚的窗台上";

	private ImageDemo() {
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

	/** OpenAI DALL·E 3。 */
	private static void demoOpenAi() {
		System.out.println("=== OpenAI DALL·E 3 ===");
		String apiKey = System.getenv("SURE_AI_OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_OPENAI_API_KEY");
			return;
		}
		try {
			ImageResponse resp = OpenAiUtil.image(OpenAiModels.DALL_E_3, PROMPT);
			printResult(resp);
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}

	/** 通义万相 wanx-v1（DashScope 异步任务，SDK 内部轮询）。 */
	private static void demoQwen() {
		System.out.println("=== 通义万相 wanx-v1 ===");
		String apiKey = System.getenv("SURE_AI_QWEN_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_QWEN_API_KEY");
			return;
		}
		try {
			ImageResponse resp = QwenUtil.image(QwenModels.WANX_V1, PROMPT);
			printResult(resp);
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}

	/** 智谱 cogview-3。 */
	private static void demoZhipu() {
		System.out.println("=== 智谱 cogview-3 ===");
		String apiKey = System.getenv("SURE_AI_ZHIPU_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("  跳过：未设置 SURE_AI_ZHIPU_API_KEY");
			return;
		}
		try {
			ImageResponse resp = ZhipuUtil.image(ZhipuModels.COGVIEW_3, PROMPT);
			printResult(resp);
		} catch (Exception e) {
			System.out.println("  调用失败：" + e.getMessage());
		}
	}

	/** 打印首张图像：URL 或 Base64 长度。 */
	private static void printResult(ImageResponse resp) {
		if (resp.data().isEmpty()) {
			System.out.println("  未返回图像（模型可能拒绝生成）");
			return;
		}
		String url = resp.firstUrl();
		if (url != null) {
			System.out.println("  图像 URL：" + url);
			return;
		}
		String b64 = resp.firstB64();
		if (b64 != null) {
			System.out.println("  返回 Base64 图像，长度：" + b64.length());
			return;
		}
		System.out.println("  结果既无 URL 也无 Base64");
	}
}
