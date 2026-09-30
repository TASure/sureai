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
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;

/**
 * 单个 AI 平台的通用配置组（对应 {@code sure.ai.<平台>.*}）。
 *
 * <p>字段与 Spring Boot Starter 的 {@code PlatformProperties} 一一对应，kebab-case 绑定：
 * {@code api-key / base-url / model / timeout / connect-timeout / proxy / organization /
 * max-retries / rate-limit-qps / cache-ttl / extra-headers / secret-key}。</p>
 *
 * <p>所有字段返回 {@link Optional}：未配置的平台组会得到全空 Optional 的实例，
 * 扩展据此判断「是否为该平台装配 Client」（对齐 Spring {@code @ConditionalOnProperty(api-key)} 语义）。</p>
 */
@ConfigGroup
public interface PlatformConfig {

	/** 平台凭证（API Key / Token）。未配置时该平台不装配 Client。 */
	Optional<String> apiKey();

	/** 第二凭证（仅百度等双密钥平台；会被映射到 extraHeaders("secretKey", ...)）。 */
	Optional<String> secretKey();

	/** 基础地址，可空。 */
	Optional<String> baseUrl();

	/** 建议默认模型名，可空。 */
	Optional<String> model();

	/** 读超时，可空。 */
	Optional<Duration> timeout();

	/** 连接超时，可空。 */
	Optional<Duration> connectTimeout();

	/** 代理 host:port，可空。 */
	Optional<String> proxy();

	/** 组织 ID，可空。 */
	Optional<String> organization();

	/** 最大重试次数，可空。 */
	Optional<Integer> maxRetries();

	/** 客户端限流 QPS（&gt;0 启用令牌桶，0/空=关闭）。 */
	Optional<Double> rateLimitQps();

	/** 缓存 TTL，可空。 */
	Optional<Duration> cacheTtl();

	/** 额外请求头，可空（透传到 AiConfig.extraHeaders）。 */
	Optional<Map<String, String>> extraHeaders();
}
