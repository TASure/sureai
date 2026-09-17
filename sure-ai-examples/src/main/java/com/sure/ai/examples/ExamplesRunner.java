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

/**
 * 示例调度入口。
 *
 * <p>接收命令行参数指定平台名，调用对应 Demo 的 main 方法。
 * 无参数时打印所有可用平台列表。平台名不区分大小写。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   java com.sure.ai.examples.ExamplesRunner openai
 *   java com.sure.ai.examples.ExamplesRunner
 * </pre>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class ExamplesRunner {

	private ExamplesRunner() {
		throw new AssertionError("No instances");
	}

	/**
	 * 入口方法。
	 *
	 * @param args args[0] 为平台名（不区分大小写），无参数时打印帮助
	 */
	public static void main(String[] args) {
		if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
			printUsage();
			return;
		}
		String platform = args[0].toLowerCase();
		String[] emptyArgs = new String[0];
		switch (platform) {
			case "openai":
				OpenAiDemo.main(emptyArgs);
				break;
			case "azure":
				AzureDemo.main(emptyArgs);
				break;
			case "anthropic":
				AnthropicDemo.main(emptyArgs);
				break;
			case "gemini":
				GeminiDemo.main(emptyArgs);
				break;
			case "deepseek":
				DeepSeekDemo.main(emptyArgs);
				break;
			case "qwen":
				QwenDemo.main(emptyArgs);
				break;
			case "zhipu":
				ZhipuDemo.main(emptyArgs);
				break;
			case "moonshot":
				MoonshotDemo.main(emptyArgs);
				break;
			case "doubao":
				DoubaoDemo.main(emptyArgs);
				break;
			case "baidu":
				BaiduDemo.main(emptyArgs);
				break;
			case "ollama":
				OllamaDemo.main(emptyArgs);
				break;
			case "rag":
				RagDemo.main(emptyArgs);
				break;
			case "image":
				ImageDemo.main(emptyArgs);
				break;
			default:
				System.out.println("未知平台：" + args[0]);
				printUsage();
		}
	}

	private static void printUsage() {
		System.out.println("sureai 示例运行器");
		System.out.println("用法：java com.sure.ai.examples.ExamplesRunner <平台名>");
		System.out.println("可用平台：");
		System.out.println("  openai     - OpenAI GPT 系列");
		System.out.println("  azure      - Azure OpenAI");
		System.out.println("  anthropic  - Anthropic Claude");
		System.out.println("  gemini     - Google Gemini");
		System.out.println("  deepseek   - DeepSeek");
		System.out.println("  qwen       - 通义千问 DashScope");
		System.out.println("  zhipu      - 智谱 GLM");
		System.out.println("  moonshot   - Moonshot Kimi");
		System.out.println("  doubao     - 火山引擎豆包");
		System.out.println("  baidu      - 百度千帆文心");
		System.out.println("  ollama     - Ollama 本地模型");
		System.out.println("  rag        - RAG 检索增强问答（基于 OpenAI）");
		System.out.println("  image      - 多平台图像生成演示（OpenAI/通义万相/智谱）");
	}
}
