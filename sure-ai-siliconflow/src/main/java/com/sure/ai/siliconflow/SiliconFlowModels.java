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

package com.sure.ai.siliconflow;

/**
 * 硅基流动 SiliconFlow OpenAI 兼容端点当前主流模型 ID 常量。
 *
 * <p>可向 {@code model} 字段传入任意模型字符串（平台为开源模型聚合托管，模型 ID 形如
 * {@code 组织/模型名}），本类常量仅为便捷参考；最新可用模型列表以官方模型库为准：
 * <a href="https://docs.siliconflow.cn/cn/userguide/capabilities/text-generation">
 * 语言模型文档</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class SiliconFlowModels {

	private SiliconFlowModels() {
		throw new AssertionError("No instances");
	}

	/** DeepSeek-V3：DeepSeek 主流通用对话模型（MoE）。 */
	public static final String DEEPSEEK_V3 = "deepseek-ai/DeepSeek-V3";

	/** Qwen2.5-72B-Instruct：通义千问 2.5 72B 指令模型。 */
	public static final String QWEN25_72B_INSTRUCT = "Qwen/Qwen2.5-72B-Instruct";

	/** Qwen3-32B：通义千问 3 代 32B 模型（支持思考/非思考切换）。 */
	public static final String QWEN3_32B = "Qwen/Qwen3-32B";

	/** BAAI/bge-m3：多语种长文本向量模型（8192 上下文，用于 /embeddings）。 */
	public static final String BGE_M3 = "BAAI/bge-m3";
}
