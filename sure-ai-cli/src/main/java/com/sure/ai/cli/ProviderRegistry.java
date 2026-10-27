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

package com.sure.ai.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.client.AiConfig;
import com.sure.ai.anthropic.AnthropicClient;
import com.sure.ai.azure.AzureClient;
import com.sure.ai.baichuan.BaichuanClient;
import com.sure.ai.baidu.BaiduClient;
import com.sure.ai.bedrock.BedrockClient;
import com.sure.ai.cohere.CohereClient;
import com.sure.ai.deepseek.DeepSeekClient;
import com.sure.ai.doubao.DoubaoClient;
import com.sure.ai.gemini.GeminiClient;
import com.sure.ai.grok.GrokClient;
import com.sure.ai.hunyuan.HunyuanClient;
import com.sure.ai.lingyi.LingyiClient;
import com.sure.ai.llamacpp.LlamaCppClient;
import com.sure.ai.minimax.MiniMaxClient;
import com.sure.ai.mistral.MistralClient;
import com.sure.ai.moonshot.MoonshotClient;
import com.sure.ai.ollama.OllamaClient;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.qwen.QwenClient;
import com.sure.ai.siliconflow.SiliconFlowClient;
import com.sure.ai.spark.SparkClient;
import com.sure.ai.stepfun.StepFunClient;
import com.sure.ai.zhipu.ZhipuClient;

/**
 * 全部 23 个平台的静态注册表：{@code --provider} 到具体 Client 的映射，与 sureai 全库对齐。
 *
 * <p>除 Bedrock 外，所有平台客户端均以 {@code (AiConfig)} 构造；Bedrock 走 SigV4 签名，
 * 需从环境变量 {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY}/{@code AWS_REGION} 读取凭证。</p>
 *
 * @author sureai
 * @since 2.0.0
 */
public final class ProviderRegistry {

	private static final Map<String, ProviderDescriptor> PLATFORMS = new LinkedHashMap<>();

	static {
		register(openai());
		register(azure());
		register(anthropic());
		register(gemini());
		register(deepseek());
		register(qwen());
		register(zhipu());
		register(moonshot());
		register(doubao());
		register(baidu());
		register(ollama());
		register(grok());
		register(mistral());
		register(llamacpp());
		register(cohere());
		register(bedrock());
		register(minimax());
		register(stepfun());
		register(baichuan());
		register(lingyi());
		register(siliconflow());
		register(hunyuan());
		register(spark());
	}

	private ProviderRegistry() {
		throw new AssertionError("No instances");
	}

	private static void register(ProviderDescriptor d) {
		PLATFORMS.put(d.name(), d);
	}

	/**
	 * 按名查找平台描述，大小写不敏感。
	 *
	 * @param name 平台名
	 * @return 描述，未知返回 null
	 */
	public static ProviderDescriptor forName(String name) {
		if (name == null) {
			return null;
		}
		return PLATFORMS.get(name.toLowerCase());
	}

	/**
	 * 全部平台描述（注册顺序即 list 输出顺序）。
	 *
	 * @return 不可变列表
	 */
	public static List<ProviderDescriptor> all() {
		return List.copyOf(PLATFORMS.values());
	}

	/** 用 apiKey/baseUrl 构造 AiConfig，baseUrl 空白则忽略。 */
	private static AiConfig configOf(String apiKey, String baseUrl) {
		AiConfig.Builder b = AiConfig.builder().apiKey(apiKey);
		if (baseUrl != null && !baseUrl.isBlank()) {
			b.baseUrl(baseUrl);
		}
		return b.build();
	}

	private static ProviderDescriptor openai() {
		return new ProviderDescriptor("openai", "OpenAI GPT 系列", "gpt-4o-mini",
			"text-embedding-3-small", "SURE_AI_OPENAI_API_KEY", true,
			(k, u, e) -> new OpenAiClient(configOf(k, u)));
	}

	private static ProviderDescriptor azure() {
		return new ProviderDescriptor("azure", "Azure OpenAI", "gpt-4o",
			null, "SURE_AI_AZURE_API_KEY", true,
			(k, u, e) -> new AzureClient(configOf(k, u)));
	}

	private static ProviderDescriptor anthropic() {
		return new ProviderDescriptor("anthropic", "Anthropic Claude", "claude-3-5-sonnet-latest",
			null, "SURE_AI_ANTHROPIC_API_KEY", true,
			(k, u, e) -> new AnthropicClient(configOf(k, u)));
	}

	private static ProviderDescriptor gemini() {
		return new ProviderDescriptor("gemini", "Google Gemini", "gemini-1.5-pro",
			null, "SURE_AI_GEMINI_API_KEY", true,
			(k, u, e) -> new GeminiClient(configOf(k, u)));
	}

	private static ProviderDescriptor deepseek() {
		return new ProviderDescriptor("deepseek", "DeepSeek", "deepseek-chat",
			null, "SURE_AI_DEEPSEEK_API_KEY", true,
			(k, u, e) -> new DeepSeekClient(configOf(k, u)));
	}

	private static ProviderDescriptor qwen() {
		return new ProviderDescriptor("qwen", "通义千问 DashScope", "qwen-plus",
			"text-embedding-v3", "SURE_AI_QWEN_API_KEY", true,
			(k, u, e) -> new QwenClient(configOf(k, u)));
	}

	private static ProviderDescriptor zhipu() {
		return new ProviderDescriptor("zhipu", "智谱 GLM", "glm-4",
			"embedding-3", "SURE_AI_ZHIPU_API_KEY", true,
			(k, u, e) -> new ZhipuClient(configOf(k, u)));
	}

	private static ProviderDescriptor moonshot() {
		return new ProviderDescriptor("moonshot", "Moonshot Kimi", "moonshot-v1-8k",
			null, "SURE_AI_MOONSHOT_API_KEY", true,
			(k, u, e) -> new MoonshotClient(configOf(k, u)));
	}

	private static ProviderDescriptor doubao() {
		return new ProviderDescriptor("doubao", "火山引擎豆包", "doubao-pro-32k",
			null, "SURE_AI_DOUBAO_API_KEY", true,
			(k, u, e) -> new DoubaoClient(configOf(k, u)));
	}

	private static ProviderDescriptor baidu() {
		return new ProviderDescriptor("baidu", "百度千帆文心", "ernie-4.0-8k",
			null, "SURE_AI_BAIDU_API_KEY", true,
			(k, u, e) -> new BaiduClient(configOf(k, u)));
	}

	private static ProviderDescriptor ollama() {
		return new ProviderDescriptor("ollama", "Ollama 本地模型", "llama3.1",
			null, null, false,
			(k, u, e) -> new OllamaClient(configOf(k, u)));
	}

	private static ProviderDescriptor grok() {
		return new ProviderDescriptor("grok", "xAI Grok", "grok-2",
			null, "SURE_AI_GROK_API_KEY", true,
			(k, u, e) -> new GrokClient(configOf(k, u)));
	}

	private static ProviderDescriptor mistral() {
		return new ProviderDescriptor("mistral", "Mistral La Plateforme", "mistral-small-latest",
			null, "SURE_AI_MISTRAL_API_KEY", true,
			(k, u, e) -> new MistralClient(configOf(k, u)));
	}

	private static ProviderDescriptor llamacpp() {
		return new ProviderDescriptor("llamacpp", "llama.cpp 本地 server", "local-model",
			null, null, false,
			(k, u, e) -> new LlamaCppClient(configOf(k, u)));
	}

	private static ProviderDescriptor cohere() {
		return new ProviderDescriptor("cohere", "Cohere Command", "command-r-plus",
			null, "SURE_AI_COHERE_API_KEY", true,
			(k, u, e) -> new CohereClient(configOf(k, u)));
	}

	private static ProviderDescriptor bedrock() {
		return new ProviderDescriptor("bedrock", "AWS Bedrock", "anthropic.claude-3-5-sonnet",
			null, "AWS_ACCESS_KEY_ID", true,
			(k, u, env) -> {
				String ak = env.getenv("AWS_ACCESS_KEY_ID");
				String sk = env.getenv("AWS_SECRET_ACCESS_KEY");
				String region = env.getenv("AWS_REGION");
				if (ak == null || ak.isBlank() || sk == null || sk.isBlank()
					|| region == null || region.isBlank()) {
					throw new CliException(CliException.EXIT_USAGE,
						"平台 bedrock 需要环境变量 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION");
				}
				return new BedrockClient(ak, sk, null, region, null);
			});
	}

	private static ProviderDescriptor minimax() {
		return new ProviderDescriptor("minimax", "MiniMax", "abab6.5s-chat",
			null, "SURE_AI_MINIMAX_API_KEY", true,
			(k, u, e) -> new MiniMaxClient(configOf(k, u)));
	}

	private static ProviderDescriptor stepfun() {
		return new ProviderDescriptor("stepfun", "阶跃星辰 Step", "step-2-16k",
			null, "SURE_AI_STEPFUN_API_KEY", true,
			(k, u, e) -> new StepFunClient(configOf(k, u)));
	}

	private static ProviderDescriptor baichuan() {
		return new ProviderDescriptor("baichuan", "百川智能", "Baichuan4",
			null, "SURE_AI_BAICHUAN_API_KEY", true,
			(k, u, e) -> new BaichuanClient(configOf(k, u)));
	}

	private static ProviderDescriptor lingyi() {
		return new ProviderDescriptor("lingyi", "零一万物 Yi", "yi-large",
			null, "SURE_AI_LINGYI_API_KEY", true,
			(k, u, e) -> new LingyiClient(configOf(k, u)));
	}

	private static ProviderDescriptor siliconflow() {
		return new ProviderDescriptor("siliconflow", "SiliconFlow 硅基流动",
			"Qwen/Qwen2.5-7B-Instruct", "BAAI/bge-small-zh-v1.5",
			"SURE_AI_SILICONFLOW_API_KEY", true,
			(k, u, e) -> new SiliconFlowClient(configOf(k, u)));
	}

	private static ProviderDescriptor hunyuan() {
		return new ProviderDescriptor("hunyuan", "腾讯混元", "hunyuan-pro",
			null, "SURE_AI_HUNYUAN_API_KEY", true,
			(k, u, e) -> new HunyuanClient(configOf(k, u)));
	}

	private static ProviderDescriptor spark() {
		return new ProviderDescriptor("spark", "讯飞星火", "generalv3.5",
			null, "SURE_AI_SPARK_API_KEY", true,
			(k, u, e) -> new SparkClient(configOf(k, u)));
	}
}
