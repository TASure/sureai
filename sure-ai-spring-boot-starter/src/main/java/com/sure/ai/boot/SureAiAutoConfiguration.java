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
import com.sure.ai.bedrock.BedrockClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.cohere.CohereClient;
import com.sure.ai.deepseek.DeepSeekClient;
import com.sure.ai.doubao.DoubaoClient;
import com.sure.ai.gemini.GeminiClient;
import com.sure.ai.grok.GrokClient;
import com.sure.ai.llamacpp.LlamaCppClient;
import com.sure.ai.mistral.MistralClient;
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
	 * <p>映射 {@code AiConfig} 中可由 yml 直接表达的字段：apiKey/baseUrl/timeout/connectTimeout/
	 * proxy/organization/maxRetries/rateLimitQps/cacheTtl/extraHeaders；
	 * {@code model} 是请求级参数，不进入 AiConfig。</p>
	 *
	 * <p>对象型扩展点（{@code cacheStore/circuitBreaker/retryListeners/metricsCollector}）
	 * 无法由字符串实例化，starter 不代为注入——用户需在自己的 {@code @Configuration} 中
	 * 声明对应 {@code @Bean} 后通过 {@code AiConfig.Builder} 编程式挂载。</p>
	 *
	 * @param p 平台属性
	 * @return AiConfig
	 */
	private static AiConfig buildConfig(PlatformProperties p) {
		AiConfig.Builder b = AiConfig.builder().apiKey(p.getApiKey());
		if (notBlank(p.getBaseUrl())) {
			b.baseUrl(p.getBaseUrl());
		}
		if (p.getConnectTimeout() != null) {
			b.connectTimeout(p.getConnectTimeout());
		}
		if (p.getTimeout() != null) {
			b.timeout(p.getTimeout());
		}
		if (notBlank(p.getProxy())) {
			b.proxy(p.getProxy());
		}
		if (notBlank(p.getOrganization())) {
			b.organization(p.getOrganization());
		}
		if (p.getMaxRetries() != null) {
			b.maxRetries(p.getMaxRetries());
		}
		if (p.getRateLimitQps() != null) {
			b.rateLimitQps(p.getRateLimitQps());
		}
		if (p.getCacheTtl() != null) {
			b.cacheTtl(p.getCacheTtl());
		}
		if (p.getExtraHeaders() != null) {
			p.getExtraHeaders().forEach(b::extraHeader);
		}
		return b.build();
	}

	/** 字符串非空判空。 */
	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
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
	 * <p>百度千帆需要「API Key + Secret Key」双凭证。{@code BaiduClient} 从
	 * {@code extraHeaders("secretKey", ...)} 读取 Secret Key，因此这里把
	 * {@code sure.ai.baidu.secret-key} 显式塞进 extraHeaders（键名对齐
	 * {@link BaiduClient#SECRET_KEY_HEADER}），其余字段仍走 {@link #buildConfig}。</p>
	 *
	 * @param props 配置
	 * @return BaiduClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.baidu", name = "api-key")
	public BaiduClient baiduClient(SureAiProperties props) {
		PlatformProperties bp = props.getBaidu();
		AiConfig.Builder b = AiConfig.builder().apiKey(bp.getApiKey());
		if (notBlank(bp.getBaseUrl())) {
			b.baseUrl(bp.getBaseUrl());
		}
		if (bp.getConnectTimeout() != null) {
			b.connectTimeout(bp.getConnectTimeout());
		}
		if (bp.getTimeout() != null) {
			b.timeout(bp.getTimeout());
		}
		if (notBlank(bp.getProxy())) {
			b.proxy(bp.getProxy());
		}
		if (bp.getMaxRetries() != null) {
			b.maxRetries(bp.getMaxRetries());
		}
		if (bp.getRateLimitQps() != null) {
			b.rateLimitQps(bp.getRateLimitQps());
		}
		if (bp.getCacheTtl() != null) {
			b.cacheTtl(bp.getCacheTtl());
		}
		if (bp.getExtraHeaders() != null) {
			bp.getExtraHeaders().forEach(b::extraHeader);
		}
		if (notBlank(bp.getSecretKey())) {
			b.extraHeader(BaiduClient.SECRET_KEY_HEADER, bp.getSecretKey());
		}
		return new BaiduClient(b.build());
	}

	/**
	 * 装配 xAI Grok 客户端（OpenAI 兼容协议）。
	 *
	 * @param props 配置
	 * @return GrokClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.grok", name = "api-key")
	public GrokClient grokClient(SureAiProperties props) {
		return new GrokClient(buildConfig(props.getGrok()));
	}

	/**
	 * 装配 Mistral AI 客户端。
	 *
	 * @param props 配置
	 * @return MistralClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.mistral", name = "api-key")
	public MistralClient mistralClient(SureAiProperties props) {
		return new MistralClient(buildConfig(props.getMistral()));
	}

	/**
	 * 装配 Llama.cpp 本地客户端（OpenAI 兼容协议）。
	 *
	 * @param props 配置
	 * @return LlamaCppClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.llamacpp", name = "api-key")
	public LlamaCppClient llamacppClient(SureAiProperties props) {
		return new LlamaCppClient(buildConfig(props.getLlamacpp()));
	}

	/**
	 * 装配 Cohere 客户端。
	 *
	 * @param props 配置
	 * @return CohereClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.cohere", name = "api-key")
	public CohereClient cohereClient(SureAiProperties props) {
		return new CohereClient(buildConfig(props.getCohere()));
	}

	/**
	 * 装配 AWS Bedrock 客户端。
	 *
	 * <p>Bedrock 不走单 API Key，条件改为 {@code sure.ai.bedrock.access-key}；
	 * 凭证四元组（accessKey/secretKey/sessionToken/region）+ 默认 modelId 直接传给
	 * {@link BedrockClient} 公开构造器。region 必填，为空由构造器抛错。</p>
	 *
	 * @param props 配置
	 * @return BedrockClient
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "sure.ai.bedrock", name = "access-key")
	public BedrockClient bedrockClient(SureAiProperties props) {
		BedrockProperties bp = props.getBedrock();
		return new BedrockClient(bp.getAccessKey(), bp.getSecretKey(), bp.getSessionToken(),
			bp.getRegion(), bp.getModel());
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
