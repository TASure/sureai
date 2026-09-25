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

package com.sure.ai.bedrock;

/**
 * AWS Bedrock 统一 Converse API 常用模型 ID 常量。
 *
 * <p>Converse API 以 {@code <provider>.<model>[:<version>]} 形式的 modelId 调用，
 * 下列常量仅为便捷参考；可向 {@code model} 传入任意 modelId。最新可用模型以官方为准：
 * <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html">
 * Supported models in Amazon Bedrock</a></p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class BedrockModels {

	private BedrockModels() {
		throw new AssertionError("No instances");
	}

	/** Anthropic Claude Sonnet 4。 */
	public static final String ANTHROPIC_CLAUDE_SONNET_4 = "anthropic.claude-sonnet-4-0:1";

	/** Anthropic Claude 3.5 Sonnet。 */
	public static final String ANTHROPIC_CLAUDE_3_5_SONNET = "anthropic.claude-3-5-sonnet-20240620-v1:0";

	/** Anthropic Claude 3.5 Haiku。 */
	public static final String ANTHROPIC_CLAUDE_3_5_HAIKU = "anthropic.claude-3-5-haiku-20241022-v1:0";

	/** Anthropic Claude 3 Opus。 */
	public static final String ANTHROPIC_CLAUDE_3_OPUS = "anthropic.claude-3-opus-20240229-v1:0";

	/** Meta Llama 3 70B Instruct。 */
	public static final String META_LLAMA3_70B = "meta.llama3-70b-instruct-v1:0";

	/** Meta Llama 3 8B Instruct。 */
	public static final String META_LLAMA3_8B = "meta.llama3-8b-instruct-v1:0";

	/** Amazon Titan Text G1 - Express。 */
	public static final String AMAZON_TITAN_TEXT_G1 = "amazon.titan-text-express-v1";

	/** Amazon Nova Pro。 */
	public static final String AMAZON_NOVA_PRO = "amazon.nova-pro-v1:0";

	/** Amazon Nova Lite。 */
	public static final String AMAZON_NOVA_LITE = "amazon.nova-lite-v1:0";

	/** Amazon Titan Text Embeddings（向量）。 */
	public static final String AMAZON_TITAN_EMBEDDINGS = "amazon.titan-embed-text-v1";
}
