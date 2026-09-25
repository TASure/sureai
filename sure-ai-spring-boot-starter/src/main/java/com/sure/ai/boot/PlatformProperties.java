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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 AI 平台的通用配置项。
 *
 * <p>所有平台共享同一组连接参数：apiKey / baseUrl / model / timeout / maxRetries，
 * 并与 {@code com.sure.ai.client.AiConfig} 的「可由 yml 直接表达」字段一一对齐：
 * connectTimeout / proxy / organization / rateLimitQps / cacheTtl / extraHeaders。</p>
 *
 * <p><b>对象型扩展点不适合写在 yml 里</b>：{@code cacheStore}、{@code circuitBreaker}、
 * {@code retryListeners}、{@code metricsCollector} 均为接口实现，Spring 无法按字符串实例化，
 * 因此本类不暴露这四个字段——用户应在自己的 {@code @Configuration} 中声明对应类型的
 * {@code @Bean}，再通过 {@code AiConfig.Builder} 编程式挂载（starter 不代为注入，避免隐式行为）。</p>
 *
 * <p>说明：{@code model} 是建议的默认模型名，仅供应用侧读取后用于
 * {@code ChatRequest.builder().model(...)}；sureai 的 {@code AiConfig} 本身不持有模型字段，
 * 模型是「每次请求」级别的参数。{@code secretKey} 仅百度等需要「双密钥」的平台使用
 * （百度会被自动装配映射到 {@code extraHeaders("secretKey", ...)}，与
 * {@code BaiduClient#SECRET_KEY_HEADER} 对齐）。</p>
 */
public class PlatformProperties {

	/** 是否启用该平台（默认 true；实际是否装配 Bean 还取决于是否配置了 api-key）。 */
	private boolean enabled = true;

	/** 平台凭证（API Key / Token）。未配置时该平台不装配 Bean。 */
	private String apiKey;

	/** 第二凭证（仅百度等双密钥平台使用；百度会被映射到 extraHeaders("secretKey", ...)）。 */
	private String secretKey;

	/** 基础地址，可空——为空时由平台客户端使用各自默认地址。 */
	private String baseUrl;

	/** 建议默认模型名，可空。 */
	private String model;

	/** 读超时，可空（空则用 AiConfig 默认 60s）。 */
	private Duration timeout;

	/** 连接超时，可空（空则用 AiConfig 默认 10s）。 */
	private Duration connectTimeout;

	/** 代理 host:port，可空。 */
	private String proxy;

	/** 组织 ID（如 OpenAI Organization），可空。 */
	private String organization;

	/** 最大重试次数，可空（空则用 AiConfig 默认 2）。 */
	private Integer maxRetries;

	/** 客户端限流 QPS（&gt;0 启用令牌桶，0/空=关闭）。 */
	private Double rateLimitQps;

	/** 缓存 TTL，可空（空则使用 CacheStore 自带默认 TTL）。 */
	private Duration cacheTtl;

	/** 额外请求头，可空（透传到 AiConfig.extraHeaders）。 */
	private Map<String, String> extraHeaders = new LinkedHashMap<>();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public String getProxy() {
		return proxy;
	}

	public void setProxy(String proxy) {
		this.proxy = proxy;
	}

	public String getOrganization() {
		return organization;
	}

	public void setOrganization(String organization) {
		this.organization = organization;
	}

	public Integer getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(Integer maxRetries) {
		this.maxRetries = maxRetries;
	}

	public Double getRateLimitQps() {
		return rateLimitQps;
	}

	public void setRateLimitQps(Double rateLimitQps) {
		this.rateLimitQps = rateLimitQps;
	}

	public Duration getCacheTtl() {
		return cacheTtl;
	}

	public void setCacheTtl(Duration cacheTtl) {
		this.cacheTtl = cacheTtl;
	}

	public Map<String, String> getExtraHeaders() {
		return extraHeaders;
	}

	public void setExtraHeaders(Map<String, String> extraHeaders) {
		this.extraHeaders = extraHeaders;
	}
}
