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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.sure.ai.baidu.BaiduClient;
import com.sure.ai.bedrock.BedrockClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.cohere.CohereClient;
import com.sure.ai.grok.GrokClient;
import com.sure.ai.llamacpp.LlamaCppClient;
import com.sure.ai.mistral.MistralClient;
import com.sure.ai.openai.OpenAiClient;
import com.sure.ai.qwen.QwenClient;

/**
 * {@link SureAiAutoConfiguration} 的轻量容器测试（ApplicationContextRunner，
 * 不起完整 Spring 容器，仅验证条件装配与属性绑定）。
 */
class SureAiAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SureAiAutoConfiguration.class));

	@Test
	void createsOpenAiClientWhenApiKeyPresent() {
		runner.withPropertyValues("sure.ai.openai.api-key=sk-test")
			.run(context -> assertThat(context).hasSingleBean(OpenAiClient.class));
	}

	@Test
	void doesNotCreateOpenAiClientWhenApiKeyMissing() {
		runner.run(context -> assertThat(context).doesNotHaveBean(OpenAiClient.class));
	}

	@Test
	void createsMultiplePlatformsWhenKeysPresent() {
		runner.withPropertyValues(
			"sure.ai.openai.api-key=sk-openai",
			"sure.ai.qwen.api-key=sk-qwen")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenAiClient.class);
				assertThat(context).hasSingleBean(QwenClient.class);
			});
	}

	@Test
	void bindsProperties() {
		runner.withPropertyValues(
			"sure.ai.openai.api-key=sk-bound",
			"sure.ai.openai.base-url=https://proxy.example.com/v1",
			"sure.ai.openai.model=gpt-4o-mini",
			"sure.ai.openai.timeout=3s",
			"sure.ai.openai.max-retries=5")
			.run(context -> {
				SureAiProperties props = context.getBean(SureAiProperties.class);
				assertThat(props.getOpenai().getApiKey()).isEqualTo("sk-bound");
				assertThat(props.getOpenai().getBaseUrl()).isEqualTo("https://proxy.example.com/v1");
				assertThat(props.getOpenai().getModel()).isEqualTo("gpt-4o-mini");
				assertThat(props.getOpenai().getTimeout()).isEqualTo(Duration.ofSeconds(3));
				assertThat(props.getOpenai().getMaxRetries()).isEqualTo(5);
			});
	}

	@Test
	void respectsConditionalOnMissingBean() {
		runner.withUserConfiguration(ManualConfig.class)
			.withPropertyValues("sure.ai.openai.api-key=sk-auto")
			.run(context -> {
				// 手动 Bean 优先：自动装配退让，容器中仍只有一个 OpenAiClient，且即手动声明的那个
				assertThat(context).hasSingleBean(OpenAiClient.class);
				assertThat(context.containsBean("manualOpenAiClient")).isTrue();
			});
	}

	// ---------- P1-8：新增 5 个平台自动装配 ----------

	@Test
	void createsGrokClientWhenApiKeyPresent() {
		runner.withPropertyValues("sure.ai.grok.api-key=sk-grok")
			.run(context -> assertThat(context).hasSingleBean(GrokClient.class));
	}

	@Test
	void createsMistralClientWhenApiKeyPresent() {
		runner.withPropertyValues("sure.ai.mistral.api-key=sk-mistral")
			.run(context -> assertThat(context).hasSingleBean(MistralClient.class));
	}

	@Test
	void createsLlamaCppClientWhenApiKeyPresent() {
		runner.withPropertyValues("sure.ai.llamacpp.api-key=llama-local",
			"sure.ai.llamacpp.base-url=http://localhost:8080/v1")
			.run(context -> assertThat(context).hasSingleBean(LlamaCppClient.class));
	}

	@Test
	void createsCohereClientWhenApiKeyPresent() {
		runner.withPropertyValues("sure.ai.cohere.api-key=sk-cohere")
			.run(context -> assertThat(context).hasSingleBean(CohereClient.class));
	}

	@Test
	void doesNotCreateNewPlatformClientsWhenKeysMissing() {
		runner.run(context -> {
			assertThat(context).doesNotHaveBean(GrokClient.class);
			assertThat(context).doesNotHaveBean(MistralClient.class);
			assertThat(context).doesNotHaveBean(LlamaCppClient.class);
			assertThat(context).doesNotHaveBean(CohereClient.class);
			assertThat(context).doesNotHaveBean(BedrockClient.class);
		});
	}

	@Test
	void createsBedrockClientWhenAccessKeyPresent() {
		runner.withPropertyValues(
			"sure.ai.bedrock.access-key=AKIA-TEST",
			"sure.ai.bedrock.secret-key=secret-test",
			"sure.ai.bedrock.region=us-east-1",
			"sure.ai.bedrock.model=anthropic.claude-3-5-sonnet-20240620-v1:0")
			.run(context -> {
				assertThat(context).hasSingleBean(BedrockClient.class);
				BedrockProperties bp = context.getBean(SureAiProperties.class).getBedrock();
				assertThat(bp.getAccessKey()).isEqualTo("AKIA-TEST");
				assertThat(bp.getSecretKey()).isEqualTo("secret-test");
				assertThat(bp.getRegion()).isEqualTo("us-east-1");
				assertThat(bp.getModel()).startsWith("anthropic.claude");
			});
	}

	@Test
	void doesNotCreateBedrockClientWhenAccessKeyMissing() {
		// 只配 region 不配 access-key：条件不满足，不应装配
		runner.withPropertyValues("sure.ai.bedrock.region=us-east-1")
			.run(context -> assertThat(context).doesNotHaveBean(BedrockClient.class));
	}

	// ---------- P1-9：PlatformProperties 与 AiConfig 字段对齐 ----------

	@Test
	void bindsExtendedPlatformProperties() {
		runner.withPropertyValues(
			"sure.ai.openai.api-key=sk-ext",
			"sure.ai.openai.connect-timeout=2s",
			"sure.ai.openai.proxy=127.0.0.1:7890",
			"sure.ai.openai.organization=org-123",
			"sure.ai.openai.rate-limit-qps=10.5",
			"sure.ai.openai.cache-ttl=5m",
			"sure.ai.openai.extra-headers.X-App=demo",
			"sure.ai.openai.extra-headers.X-Trace=t-1")
			.run(context -> {
				PlatformProperties p = context.getBean(SureAiProperties.class).getOpenai();
				assertThat(p.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
				assertThat(p.getProxy()).isEqualTo("127.0.0.1:7890");
				assertThat(p.getOrganization()).isEqualTo("org-123");
				assertThat(p.getRateLimitQps()).isEqualTo(10.5);
				assertThat(p.getCacheTtl()).isEqualTo(Duration.ofMinutes(5));
				assertThat(p.getExtraHeaders())
					.containsEntry("X-App", "demo")
					.containsEntry("X-Trace", "t-1");
			});
	}

	@Test
	void mapsExtendedFieldsIntoAiConfig() {
		runner.withPropertyValues(
			"sure.ai.openai.api-key=sk-map",
			"sure.ai.openai.connect-timeout=2s",
			"sure.ai.openai.proxy=127.0.0.1:7890",
			"sure.ai.openai.organization=org-123",
			"sure.ai.openai.rate-limit-qps=10.5",
			"sure.ai.openai.cache-ttl=5m",
			"sure.ai.openai.timeout=30s",
			"sure.ai.openai.max-retries=4",
			"sure.ai.openai.extra-headers.X-App=demo")
			.run(context -> {
				OpenAiClient client = context.getBean(OpenAiClient.class);
				AiConfig config = (AiConfig) ReflectionTestUtils.getField(client, "config");
				assertThat(config).isNotNull();
				assertThat(config.apiKey()).isEqualTo("sk-map");
				assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
				assertThat(config.proxy()).isEqualTo("127.0.0.1:7890");
				assertThat(config.organization()).isEqualTo("org-123");
				assertThat(config.rateLimitQps()).isEqualTo(10.5);
				assertThat(config.cacheTtl()).isEqualTo(Duration.ofMinutes(5));
				assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(30));
				assertThat(config.maxRetries()).isEqualTo(4);
				assertThat(config.extraHeaders()).containsEntry("X-App", "demo");
			});
	}

	// ---------- P1-10：Baidu secretKey Spring 配置 ----------

	@Test
	void bindsBaiduSecretKeyProperty() {
		runner.withPropertyValues(
			"sure.ai.baidu.api-key=api-xxx",
			"sure.ai.baidu.secret-key=secret-yyy")
			.run(context -> {
				PlatformProperties p = context.getBean(SureAiProperties.class).getBaidu();
				assertThat(p.getApiKey()).isEqualTo("api-xxx");
				assertThat(p.getSecretKey()).isEqualTo("secret-yyy");
			});
	}

	@Test
	void baiduSecretKeyIsPassedToClient() {
		runner.withPropertyValues(
			"sure.ai.baidu.api-key=api-xxx",
			"sure.ai.baidu.secret-key=secret-yyy")
			.run(context -> {
				assertThat(context).hasSingleBean(BaiduClient.class);
				BaiduClient client = context.getBean(BaiduClient.class);
				// BaiduClient.secretKey 由构造器从 extraHeaders("secretKey") 读取
				assertThat(ReflectionTestUtils.getField(client, "secretKey"))
					.isEqualTo("secret-yyy");
				// 同时验证 extraHeaders 中确实写入了 secretKey（与 BaiduClient.SECRET_KEY_HEADER 对齐）
				AiConfig config = (AiConfig) ReflectionTestUtils.getField(client, "config");
				assertThat(config.extraHeaders())
					.containsEntry(BaiduClient.SECRET_KEY_HEADER, "secret-yyy");
			});
	}

	/** 手动声明一个 OpenAiClient Bean，验证自动装配退让。 */
	@org.springframework.context.annotation.Configuration
	static class ManualConfig {

		@org.springframework.context.annotation.Bean
		OpenAiClient manualOpenAiClient() {
			return new OpenAiClient(com.sure.ai.client.AiConfig.of("sk-manual"));
		}
	}
}
