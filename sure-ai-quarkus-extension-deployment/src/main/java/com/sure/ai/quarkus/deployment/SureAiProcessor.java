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

package com.sure.ai.quarkus.deployment;

import java.util.List;
import java.util.function.Function;

import jakarta.inject.Singleton;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.runtime.RuntimeValue;

import com.sure.ai.bedrock.BedrockClient;
import com.sure.ai.quarkus.BedrockGroup;
import com.sure.ai.quarkus.BedrockSpec;
import com.sure.ai.quarkus.ClientSpec;
import com.sure.ai.quarkus.PlatformConfig;
import com.sure.ai.quarkus.SureAiBuildConfig;
import com.sure.ai.quarkus.SureAiClientFactory;
import com.sure.ai.quarkus.SureAiRecorder;

/**
 * sureai Quarkus 扩展构建期处理器。
 *
 * <p>在构建期（augmentation）读取 {@link SureAiBuildConfig}（前缀 {@code sure.ai}），
 * 为<b>配置了 {@code api-key} 的平台</b>通过 {@link SyntheticBeanBuildItem} 注册对应的
 * {@code XxxClient} 为 Arc 合成 Bean（{@link Singleton} 作用域）；未配置 key 的平台不注册，
 * 对齐 Spring {@code @ConditionalOnProperty(prefix="sure.ai.x", name="api-key")} 语义。</p>
 *
 * <p>真正的客户端实例化被 {@link Record} 录制到运行期 {@link ExecutionTime#STATIC_INIT}，
 * 经 {@link SureAiRecorder} 委托 {@link SureAiClientFactory} 完成——与手动
 * {@code new OpenAiClient(AiConfig.of(key))} 完全等价，实例化不触发任何网络。</p>
 */
public class SureAiProcessor {

	private static final String FEATURE = "sure-ai";

	/** 平台名 → 配置访问器（与 {@link SureAiClientFactory#PLATFORM_CLIENT_CLASSES} 同序同集）。 */
	private static final List<PlatformDef> PLATFORMS = List.of(
		new PlatformDef("openai", SureAiBuildConfig::openai),
		new PlatformDef("azure", SureAiBuildConfig::azure),
		new PlatformDef("anthropic", SureAiBuildConfig::anthropic),
		new PlatformDef("gemini", SureAiBuildConfig::gemini),
		new PlatformDef("deepseek", SureAiBuildConfig::deepseek),
		new PlatformDef("qwen", SureAiBuildConfig::qwen),
		new PlatformDef("zhipu", SureAiBuildConfig::zhipu),
		new PlatformDef("moonshot", SureAiBuildConfig::moonshot),
		new PlatformDef("doubao", SureAiBuildConfig::doubao),
		new PlatformDef("baidu", SureAiBuildConfig::baidu),
		new PlatformDef("ollama", SureAiBuildConfig::ollama),
		new PlatformDef("grok", SureAiBuildConfig::grok),
		new PlatformDef("mistral", SureAiBuildConfig::mistral),
		new PlatformDef("llamacpp", SureAiBuildConfig::llamacpp),
		new PlatformDef("cohere", SureAiBuildConfig::cohere),
		new PlatformDef("minimax", SureAiBuildConfig::minimax),
		new PlatformDef("stepfun", SureAiBuildConfig::stepfun),
		new PlatformDef("baichuan", SureAiBuildConfig::baichuan),
		new PlatformDef("lingyi", SureAiBuildConfig::lingyi),
		new PlatformDef("siliconflow", SureAiBuildConfig::siliconflow),
		new PlatformDef("hunyuan", SureAiBuildConfig::hunyuan),
		new PlatformDef("spark", SureAiBuildConfig::spark));

	/**
	 * 注册扩展特性（启动日志 Installed features 中可见 {@code sure-ai}）。
	 *
	 * @return FeatureBuildItem
	 */
	@BuildStep
	FeatureBuildItem feature() {
		return new FeatureBuildItem(FEATURE);
	}

	/**
	 * 按配置为各平台注册客户端合成 Bean。
	 *
	 * @param config   构建期配置根
	 * @param recorder 运行期 recorder
	 * @param beans    合成 Bean 生产者
	 */
	@BuildStep
	@Record(ExecutionTime.STATIC_INIT)
	public void configureClients(SureAiBuildConfig config, SureAiRecorder recorder,
			BuildProducer<SyntheticBeanBuildItem> beans) {
		for (PlatformDef def : PLATFORMS) {
			PlatformConfig pc = def.accessor.apply(config);
			if (pc == null || pc.apiKey().isEmpty()) {
				continue;
			}
			String clientClassName = SureAiClientFactory.PLATFORM_CLIENT_CLASSES.get(def.name());
			ClientSpec spec = toSpec(pc);
			RuntimeValue<Object> value = recorder.createClient(clientClassName, spec);
			beans.produce(SyntheticBeanBuildItem.configure(classForName(clientClassName))
				.scope(Singleton.class)
				.runtimeValue(value)
				.done());
		}
		registerBedrock(config.bedrock(), recorder, beans);
	}

	/**
	 * Bedrock 特殊装配：条件为 {@code sure.ai.bedrock.access-key}，四元组凭证直传。
	 */
	private static void registerBedrock(BedrockGroup bg, SureAiRecorder recorder,
			BuildProducer<SyntheticBeanBuildItem> beans) {
		if (bg == null || bg.accessKey().isEmpty()) {
			return;
		}
		BedrockSpec spec = new BedrockSpec();
		bg.accessKey().ifPresent(spec::setAccessKey);
		bg.secretKey().ifPresent(spec::setSecretKey);
		bg.sessionToken().ifPresent(spec::setSessionToken);
		bg.region().ifPresent(spec::setRegion);
		bg.model().ifPresent(spec::setModel);
		RuntimeValue<Object> value = recorder.createBedrock(spec);
		beans.produce(SyntheticBeanBuildItem.configure(BedrockClient.class)
			.scope(Singleton.class)
			.runtimeValue(value)
			.done());
	}

	/** 把 {@link PlatformConfig} 拍平为可跨期记录的 {@link ClientSpec}。 */
	private static ClientSpec toSpec(PlatformConfig pc) {
		ClientSpec s = new ClientSpec();
		pc.apiKey().ifPresent(s::setApiKey);
		pc.secretKey().ifPresent(s::setSecretKey);
		pc.baseUrl().ifPresent(s::setBaseUrl);
		pc.model().ifPresent(s::setModel);
		pc.proxy().ifPresent(s::setProxy);
		pc.organization().ifPresent(s::setOrganization);
		pc.maxRetries().ifPresent(s::setMaxRetries);
		pc.rateLimitQps().ifPresent(s::setRateLimitQps);
		pc.timeout().ifPresent(d -> s.setTimeoutMillis(d.toMillis()));
		pc.connectTimeout().ifPresent(d -> s.setConnectTimeoutMillis(d.toMillis()));
		pc.cacheTtl().ifPresent(d -> s.setCacheTtlMillis(d.toMillis()));
		pc.extraHeaders().ifPresent(s::setExtraHeaders);
		return s;
	}

	/** 按全限定名加载客户端类（构建期类路径已含 sure-ai-all 全部平台模块）。 */
	private static Class<?> classForName(String name) {
		try {
			return Class.forName(name, false, SureAiProcessor.class.getClassLoader());
		}
		catch (ClassNotFoundException e) {
			throw new IllegalStateException("找不到平台客户端类: " + name, e);
		}
	}

	/** 平台定义：名字 + 配置访问器。 */
	private record PlatformDef(String name, Function<SureAiBuildConfig, PlatformConfig> accessor) {
	}
}
