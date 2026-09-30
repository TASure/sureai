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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个平台客户端的「可跨构建期→运行期记录」的数据载体。
 *
 * <p>Quarkus 字节码录制（bytecode recording）要求 recorder 方法参数可被录制：
 * 仅允许基本类型、String、Class、带无参构造 + getter/setter 的 POJO、以及它们的数组/List/Map。
 * {@code java.time.Duration} 不在可录制白名单内，因此这里统一以「毫秒数（long）」传递时长，
 * 并以 {@code -1} 表示「未配置」；数值型同样以 {@code -1} 表示缺省。</p>
 *
 * <p>本类<b>不引入任何 Quarkus 类型</b>，与 {@link SureAiClientFactory} 一起构成纯 Java 可单测逻辑层；
 * deployment 模块在构建期把 {@code sure.ai.<平台>.*} 配置拍平成本对象后，再交给 recorder 在运行期实例化客户端。</p>
 */
public class ClientSpec {

	/** 平台凭证（API Key / Token）。必填。 */
	private String apiKey;

	/** 基础地址，可空。 */
	private String baseUrl;

	/** 建议默认模型名，可空（请求级参数，不进入 AiConfig，仅随 spec 透传备用）。 */
	private String model;

	/** 代理 host:port，可空。 */
	private String proxy;

	/** 组织 ID，可空。 */
	private String organization;

	/** 第二凭证（仅百度双密钥）；存在时会被映射到 extraHeaders("secretKey", ...)。 */
	private String secretKey;

	/** 读超时毫秒数，{@code -1} 表示未配置。 */
	private long timeoutMillis = -1;

	/** 连接超时毫秒数，{@code -1} 表示未配置。 */
	private long connectTimeoutMillis = -1;

	/** 缓存 TTL 毫秒数，{@code -1} 表示未配置。 */
	private long cacheTtlMillis = -1;

	/** 最大重试次数，{@code -1} 表示未配置。 */
	private int maxRetries = -1;

	/** 限流 QPS（默认 0=关闭）。 */
	private double rateLimitQps;

	/** 额外请求头。 */
	private Map<String, String> extraHeaders = new LinkedHashMap<>();

	/** 无参构造器（Quarkus 录制要求）。 */
	public ClientSpec() {
	}

	public String getApiKey() {
		return this.apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getBaseUrl() {
		return this.baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getProxy() {
		return this.proxy;
	}

	public void setProxy(String proxy) {
		this.proxy = proxy;
	}

	public String getOrganization() {
		return this.organization;
	}

	public void setOrganization(String organization) {
		this.organization = organization;
	}

	public String getSecretKey() {
		return this.secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public long getTimeoutMillis() {
		return this.timeoutMillis;
	}

	public void setTimeoutMillis(long timeoutMillis) {
		this.timeoutMillis = timeoutMillis;
	}

	public long getConnectTimeoutMillis() {
		return this.connectTimeoutMillis;
	}

	public void setConnectTimeoutMillis(long connectTimeoutMillis) {
		this.connectTimeoutMillis = connectTimeoutMillis;
	}

	public long getCacheTtlMillis() {
		return this.cacheTtlMillis;
	}

	public void setCacheTtlMillis(long cacheTtlMillis) {
		this.cacheTtlMillis = cacheTtlMillis;
	}

	public int getMaxRetries() {
		return this.maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public double getRateLimitQps() {
		return this.rateLimitQps;
	}

	public void setRateLimitQps(double rateLimitQps) {
		this.rateLimitQps = rateLimitQps;
	}

	public Map<String, String> getExtraHeaders() {
		return this.extraHeaders;
	}

	public void setExtraHeaders(Map<String, String> extraHeaders) {
		this.extraHeaders = extraHeaders;
	}
}
