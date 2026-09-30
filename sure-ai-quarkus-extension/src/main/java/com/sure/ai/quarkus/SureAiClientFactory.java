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

package com.sure.ai.quarkus;

import java.time.Duration;
import java.util.Map;

import com.sure.ai.baidu.BaiduClient;
import com.sure.ai.bedrock.BedrockClient;
import com.sure.ai.client.AiConfig;

/**
 * sureai Quarkus 扩展的纯逻辑工厂：把 {@link ClientSpec} 拍平配置构建为 {@link AiConfig}，
 * 并按客户端全限定名反射实例化对应平台 Client。
 *
 * <p>本类<b>不引入任何 Quarkus 类型</b>，因此可在不启动 Quarkus 容器的前提下做单元测试；
 * deployment 模块的 {@code SureAiProcessor}（构建期）与 {@link SureAiRecorder}（运行期）
 * 都是对本类的薄封装。这与 Spring Boot Starter 中「读配置 → 构建 AiConfig → new 客户端」三步等价，
 * 不引入任何额外运行期行为，也不改变各平台客户端语义。</p>
 *
 * <p>平台 → 客户端类映射表 {@link #PLATFORM_CLIENT_CLASSES} 覆盖全部 22 个可经 {@code AiConfig} 构造的平台；
 * Bedrock 因构造签名独立（四元组凭证）走 {@link #newBedrock(BedrockSpec)}。</p>
 */
public final class SureAiClientFactory {

	/**
	 * 平台名（即 {@code sure.ai.<平台>} 段）→ 客户端类全限定名。
	 *
	 * <p>除 Bedrock 外，其余 22 个平台客户端均提供 {@code XxxClient(AiConfig)} 构造器。</p>
	 */
	public static final Map<String, String> PLATFORM_CLIENT_CLASSES = MapEntries.build();

	private SureAiClientFactory() {
	}

	/**
	 * 由通用平台 spec 构建 AiConfig。
	 *
	 * <p>映射 {@link AiConfig} 中可由 yml 直接表达的字段；对象型扩展点
	 * （cacheStore/circuitBreaker/retryListeners/metricsCollector）无法由字符串实例化，
	 * 扩展不代为注入——用户应在自己的 CDI 中声明对应 Bean 后通过 {@link AiConfig.Builder} 编程式挂载。</p>
	 *
	 * @param spec 平台拍平配置
	 * @return AiConfig
	 */
	public static AiConfig toAiConfig(ClientSpec spec) {
		AiConfig.Builder b = AiConfig.builder().apiKey(spec.getApiKey());
		if (notBlank(spec.getBaseUrl())) {
			b.baseUrl(spec.getBaseUrl());
		}
		if (spec.getConnectTimeoutMillis() >= 0) {
			b.connectTimeout(Duration.ofMillis(spec.getConnectTimeoutMillis()));
		}
		if (spec.getTimeoutMillis() >= 0) {
			b.timeout(Duration.ofMillis(spec.getTimeoutMillis()));
		}
		if (notBlank(spec.getProxy())) {
			b.proxy(spec.getProxy());
		}
		if (notBlank(spec.getOrganization())) {
			b.organization(spec.getOrganization());
		}
		if (spec.getMaxRetries() >= 0) {
			b.maxRetries(spec.getMaxRetries());
		}
		if (spec.getRateLimitQps() > 0) {
			b.rateLimitQps(spec.getRateLimitQps());
		}
		if (spec.getCacheTtlMillis() >= 0) {
			b.cacheTtl(Duration.ofMillis(spec.getCacheTtlMillis()));
		}
		if (spec.getExtraHeaders() != null) {
			spec.getExtraHeaders().forEach(b::extraHeader);
		}
		// 百度等双密钥平台：secretKey 映射到 extraHeaders("secretKey", ...)，与 BaiduClient#SECRET_KEY_HEADER 对齐
		if (notBlank(spec.getSecretKey())) {
			b.extraHeader(BaiduClient.SECRET_KEY_HEADER, spec.getSecretKey());
		}
		return b.build();
	}

	/**
	 * 反射实例化一个 {@code AiConfig} 族平台客户端。
	 *
	 * <p>构造器为 {@code XxxClient(AiConfig)}。实例化本身不触发任何网络请求
	 * （与手动 {@code new OpenAiClient(AiConfig.of(key))} 等价）。</p>
	 *
	 * @param clientClassName 客户端类全限定名
	 * @param spec            平台拍平配置
	 * @return 客户端实例
	 */
	public static Object newClient(String clientClassName, ClientSpec spec) {
		AiConfig config = toAiConfig(spec);
		try {
			Class<?> clazz = Class.forName(clientClassName, true, SureAiClientFactory.class.getClassLoader());
			return clazz.getConstructor(AiConfig.class).newInstance(config);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException("无法实例化平台客户端: " + clientClassName, e);
		}
	}

	/**
	 * 实例化 AWS Bedrock 客户端（SigV4 四元组凭证）。
	 *
	 * @param spec Bedrock 拍平配置
	 * @return BedrockClient
	 */
	public static BedrockClient newBedrock(BedrockSpec spec) {
		return new BedrockClient(spec.getAccessKey(), spec.getSecretKey(), spec.getSessionToken(),
			spec.getRegion(), spec.getModel());
	}

	/** 字符串非空判空。 */
	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	/** 平台→客户端类映射表（静态构造，避免在字段初始化处塞 22 行字面量）。 */
	static final class MapEntries {

		static Map<String, String> build() {
			Map<String, String> m = new java.util.LinkedHashMap<>();
			m.put("openai", "com.sure.ai.openai.OpenAiClient");
			m.put("azure", "com.sure.ai.azure.AzureClient");
			m.put("anthropic", "com.sure.ai.anthropic.AnthropicClient");
			m.put("gemini", "com.sure.ai.gemini.GeminiClient");
			m.put("deepseek", "com.sure.ai.deepseek.DeepSeekClient");
			m.put("qwen", "com.sure.ai.qwen.QwenClient");
			m.put("zhipu", "com.sure.ai.zhipu.ZhipuClient");
			m.put("moonshot", "com.sure.ai.moonshot.MoonshotClient");
			m.put("doubao", "com.sure.ai.doubao.DoubaoClient");
			m.put("baidu", "com.sure.ai.baidu.BaiduClient");
			m.put("ollama", "com.sure.ai.ollama.OllamaClient");
			m.put("grok", "com.sure.ai.grok.GrokClient");
			m.put("mistral", "com.sure.ai.mistral.MistralClient");
			m.put("llamacpp", "com.sure.ai.llamacpp.LlamaCppClient");
			m.put("cohere", "com.sure.ai.cohere.CohereClient");
			m.put("minimax", "com.sure.ai.minimax.MiniMaxClient");
			m.put("stepfun", "com.sure.ai.stepfun.StepFunClient");
			m.put("baichuan", "com.sure.ai.baichuan.BaichuanClient");
			m.put("lingyi", "com.sure.ai.lingyi.LingyiClient");
			m.put("siliconflow", "com.sure.ai.siliconflow.SiliconFlowClient");
			m.put("hunyuan", "com.sure.ai.hunyuan.HunyuanClient");
			m.put("spark", "com.sure.ai.spark.SparkClient");
			return java.util.Collections.unmodifiableMap(m);
		}

		private MapEntries() {
		}
	}
}
