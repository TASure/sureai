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

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * sureai Quarkus 扩展配置根（前缀 {@code sure.ai}，构建期 {@link ConfigPhase#BUILD_TIME}）。
 *
 * <p>与 Spring Boot Starter 的 {@code SureAiProperties} 同构：各平台复用 {@link PlatformConfig}，
 * 通过具名嵌套组区分（{@code sure.ai.openai.*}、{@code sure.ai.qwen.*} ...）；Bedrock 独立成组。
 * 用字面量访问器而非 Map，是为了在构建期对每个平台做明确的「是否配置了 api-key」判断（对齐
 * Spring {@code @ConditionalOnProperty(prefix="sure.ai.openai", name="api-key")}）。</p>
 *
 * <p>示例：<pre>{@code
 * sure:
 *   ai:
 *     openai:
 *       api-key: sk-xxx
 *       model: gpt-4o-mini
 *     qwen:
 *       api-key: sk-yyy
 *       base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 * }</pre>
 */
@ConfigMapping(prefix = "sure.ai")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface SureAiBuildConfig {

	/** OpenAI。 */
	PlatformConfig openai();

	/** Azure OpenAI。 */
	PlatformConfig azure();

	/** Anthropic（Claude）。 */
	PlatformConfig anthropic();

	/** Gemini（Google）。 */
	PlatformConfig gemini();

	/** DeepSeek。 */
	PlatformConfig deepseek();

	/** 通义千问。 */
	PlatformConfig qwen();

	/** 智谱。 */
	PlatformConfig zhipu();

	/** Moonshot（Kimi）。 */
	PlatformConfig moonshot();

	/** 豆包 / 火山引擎。 */
	PlatformConfig doubao();

	/** 百度智能云（双密钥）。 */
	PlatformConfig baidu();

	/** Ollama（本地服务）。 */
	PlatformConfig ollama();

	/** xAI Grok。 */
	PlatformConfig grok();

	/** Mistral AI。 */
	PlatformConfig mistral();

	/** Llama.cpp（本地兼容服务）。 */
	PlatformConfig llamacpp();

	/** Cohere。 */
	PlatformConfig cohere();

	/** MiniMax。 */
	PlatformConfig minimax();

	/** 阶跃星辰 StepFun。 */
	PlatformConfig stepfun();

	/** 百川 Baichuan。 */
	PlatformConfig baichuan();

	/** 零一万物 Lingyi。 */
	PlatformConfig lingyi();

	/** SiliconFlow 硅基流动。 */
	PlatformConfig siliconflow();

	/** 腾讯混元 Hunyuan。 */
	PlatformConfig hunyuan();

	/** 讯飞星火 Spark。 */
	PlatformConfig spark();

	/** AWS Bedrock（SigV4 四元组，独立组）。 */
	BedrockGroup bedrock();
}
