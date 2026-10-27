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

package com.sure.ai.llamacpp;

/**
 * llama.cpp server 常见模型名常量。
 *
 * <p><b>注意：</b>llama.cpp server 实际暴露的模型 ID 由启动时的 {@code -a alias}
 * 参数决定；以下收录社区常用模型名作为参考，实际传入的字符串必须与
 * server 启动时 {@code -a} 指定的 alias 一致。可用 {@code GET /v1/models} 查看当前加载的模型列表。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class LlamaCppModels {

	/** Llama 3.1 8B — Meta 开源 8B 对话模型。 */
	public static final String LLAMA_3_1_8B = "llama-3.1-8b";

	/** Llama 3.1 70B — Meta 开源 70B 对话模型。 */
	public static final String LLAMA_3_1_70B = "llama-3.1-70b";

	/** Qwen 2.5 7B — 通义千问开源 7B 模型。 */
	public static final String QWEN2_5_7B = "qwen2.5-7b";

	/** Qwen 2.5 14B — 通义千问开源 14B 模型。 */
	public static final String QWEN2_5_14B = "qwen2.5-14b";

	/** Mistral 7B — Mistral AI 开源 7B 模型。 */
	public static final String MISTRAL_7B = "mistral-7b";

	/** Phi 4 — 微软小参数模型。 */
	public static final String PHI_4 = "phi-4";

	/** BGE M3 — 常用中文/多语言向量模型（需 --embedding 启动）。 */
	public static final String BGE_M3 = "bge-m3";

	private LlamaCppModels() {
		throw new AssertionError("No instances");
	}
}
