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

package com.sure.ai.ollama;

/**
 * Ollama 常见模型名常量。
 *
 * <p>Ollama 模型需先通过 {@code ollama pull <name>} 下载到本地；以下收录社区常用模型名作为参考，
 * 可传入任意已 pull 的模型字符串。完整模型列表见
 * <a href="https://ollama.com/library">Ollama Library</a>。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class OllamaModels {

	/** Llama 3.2 — Meta 轻量对话模型。 */
	public static final String LLAMA3_2 = "llama3.2";

	/** Qwen 2.5 — 通义千问开源版。 */
	public static final String QWEN2_5 = "qwen2.5";

	/** Gemma 2 — Google 轻量模型。 */
	public static final String GEMMA2 = "gemma2";

	/** Mistral — Mistral AI 模型。 */
	public static final String MISTRAL = "mistral";

	/** Phi 3 — 微软小参数模型。 */
	public static final String PHI3 = "phi3";

	private OllamaModels() {
		throw new AssertionError("No instances");
	}
}
