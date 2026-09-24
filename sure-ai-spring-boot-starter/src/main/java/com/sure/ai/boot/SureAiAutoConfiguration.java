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

package com.sure.ai.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.sure.ai.anthropic.AnthropicClient;
import com.sure.ai.azure.AzureClient;
import com.sure.ai.baidu.BaiduClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.deepseek.DeepSeekClient;
import com.sure.ai.doubao.DoubaoClient;
import com.sure.ai.gemini.GeminiClient;
import com.sure.ai.moonshot.MoonshotClient;
import com.sure.ai.ollama.OllamaClient;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.qwen.QwenClient;
import com.sure.ai.zhipu.ZhipuClient;

/**
 * sureai Spring Boot 自动装配入口。
 *
 * <p>通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 被 Spring Boot 自动发现。为每个平台注册一个 {@code XxxClient} Bean：
 * 当且仅当该平台配置了 {@code sure.ai.<平台>.api-key} 时才装配；
 * 未配置 key 的平台不出现在容器中，互不影响。</p>
 *
 * <p><b>等价性</b>：本装配与手动编写
 * {@code new OpenAiClient(AiConfig.builder().apiKey(...).baseUrl(...).build())}
 * 完全等价——starter 只是把「读配置 → 构建 AiConfig → new 客户端」这三步自动化，
 * 不引入任何额外运行期行为，也不改变各平台客户端语义。</p>
 *
 * <p><b>红线</b>：Spring 依赖仅存在于本 starter 模块；sure-ai-core 及各平台模块
 * 运行期零第三方依赖，仍可在非 Spring 环境独立使用。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(SureAiProperties.class)
public class SureAiAutoConfiguration {

	/**
	 * 由通用平台属性构建 AiConfig。
	 *
	 * <p>仅映射 AiConfig 真正持有的字段（apiKey/baseUrl/timeout/maxRetries）；
	 * {@code model} 是请求级参数，不进入 AiConfig。</p>
	 *
	 * @param p 平台属性
	 * @return AiConfig
	 */
	private static AiConfig buildConfig(PlatformProperties p) {
		AiConfig.Builder b = AiConfig.builder().apiKey(p.getApiKey());
		if (p.getBaseUrl() != null && !p.getBaseUrl().isBlank()) {
			b.baseUrl(p.getBaseUrl());
		}
		if (p.getTimeout() != null) {
			b.timeout(p.getTimeout());
		}
		if (p.getMaxRetries() != null) {
			b.maxRetries(p.getMaxRetries());
		}
		return b.build();
	}

	/**
	 * 装配 OpenAI 客户端。
	 *
	 * @param props 配置
	 * @return OpenAiClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.openai", name = "api-key")
	public OpenAiClient openAiClient(SureAiProperties props) {
		return new OpenAiClient(buildConfig(props.getOpenai()));
	}

	/**
	 * 装配 Azure 客户端。
	 *
	 * @param props 配置
	 * @return AzureClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.azure", name = "api-key")
	public AzureClient azureClient(SureAiProperties props) {
		return new AzureClient(buildConfig(props.getAzure()));
	}

	/**
	 * 装配 Anthropic 客户端。
	 *
	 * @param props 配置
	 * @return AnthropicClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.anthropic", name = "api-key")
	public AnthropicClient anthropicClient(SureAiProperties props) {
		return new AnthropicClient(buildConfig(props.getAnthropic()));
	}

	/**
	 * 装配 Gemini 客户端。
	 *
	 * @param props 配置
	 * @return GeminiClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.gemini", name = "api-key")
	public GeminiClient geminiClient(SureAiProperties props) {
		return new GeminiClient(buildConfig(props.getGemini()));
	}

	/**
	 * 装配 DeepSeek 客户端。
	 *
	 * @param props 配置
	 * @return DeepSeekClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.deepseek", name = "api-key")
	public DeepSeekClient deepseekClient(SureAiProperties props) {
		return new DeepSeekClient(buildConfig(props.getDeepseek()));
	}

	/**
	 * 装配通义千问客户端。
	 *
	 * @param props 配置
	 * @return QwenClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.qwen", name = "api-key")
	public QwenClient qwenClient(SureAiProperties props) {
		return new QwenClient(buildConfig(props.getQwen()));
	}

	/**
	 * 装配智谱客户端。
	 *
	 * @param props 配置
	 * @return ZhipuClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.zhipu", name = "api-key")
	public ZhipuClient zhipuClient(SureAiProperties props) {
		return new ZhipuClient(buildConfig(props.getZhipu()));
	}

	/**
	 * 装配 Moonshot 客户端。
	 *
	 * @param props 配置
	 * @return MoonshotClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.moonshot", name = "api-key")
	public MoonshotClient moonshotClient(SureAiProperties props) {
		return new MoonshotClient(buildConfig(props.getMoonshot()));
	}

	/**
	 * 装配豆包 / 火山引擎客户端。
	 *
	 * @param props 配置
	 * @return DoubaoClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.doubao", name = "api-key")
	public DoubaoClient doubaoClient(SureAiProperties props) {
		return new DoubaoClient(buildConfig(props.getDoubao()));
	}

	/**
	 * 装配百度智能云客户端。
	 *
	 * @param props 配置
	 * @return BaiduClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.baidu", name = "api-key")
	public BaiduClient baiduClient(SureAiProperties props) {
		return new BaiduClient(buildConfig(props.getBaidu()));
	}

	/**
	 * 装配 Ollama 本地客户端。
	 *
	 * <p>Ollama 为本地服务无需真实凭证，按 {@code OllamaUtil} 惯例可填任意占位
	 * api-key（如 {@code ollama-local}），并通过 base-url 指向实际地址。</p>
	 *
	 * @param props 配置
	 * @return OllamaClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.ollama", name = "api-key")
	public OllamaClient ollamaClient(SureAiProperties props) {
		return new OllamaClient(buildConfig(props.getOllama()));
	}
}
