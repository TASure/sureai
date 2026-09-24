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

	/** 手动声明一个 OpenAiClient Bean，验证自动装配退让。 */
	@org.springframework.context.annotation.Configuration
	static class ManualConfig {

		@org.springframework.context.annotation.Bean
		OpenAiClient manualOpenAiClient() {
			return new OpenAiClient(com.sure.ai.client.AiConfig.of("sk-manual"));
		}
	}
}
