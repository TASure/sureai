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

/**
 * AWS Bedrock 平台配置项。
 *
 * <p>Bedrock 不走单 API Key 鉴权，而使用 AWS SigV4 四元组
 * （accessKey / secretKey / sessionToken / region），因此不能复用通用的
 * {@link PlatformProperties}，单独建类。字段与 {@code BedrockUtil} 读取的环境变量
 * （{@code SURE_AI_BEDROCK_ACCESS_KEY/SECRET_KEY/SESSION_TOKEN/REGION/MODEL}，
 * 及兜底的 {@code AWS_ACCESS_KEY_ID} 等）一一对应。</p>
 *
 * <p>装配条件：{@code sure.ai.bedrock.access-key} 存在即注册 {@code BedrockClient}；
 * region 为必填，为空时 {@code BedrockClient} 构造器会直接抛错。</p>
 *
 * <p>说明：{@code timeout} / {@code maxRetries} 当前仅作配置占位——
 * {@code BedrockClient} 自建 JDK HttpClient、未暴露这两个参数，保留字段是为了与其它平台
 * 配置风格一致及未来扩展。</p>
 */
public class BedrockProperties {

	/** 是否启用 Bedrock 装配（默认 true；实际是否装配 Bean 还取决于是否配置了 access-key）。 */
	private boolean enabled = true;

	/** AWS Access Key ID（必填，对应 SURE_AI_BEDROCK_ACCESS_KEY / AWS_ACCESS_KEY_ID）。 */
	private String accessKey;

	/** AWS Secret Access Key（必填，对应 SURE_AI_BEDROCK_SECRET_KEY / AWS_SECRET_ACCESS_KEY）。 */
	private String secretKey;

	/** 临时会话令牌（可选，对应 SURE_AI_BEDROCK_SESSION_TOKEN / AWS_SESSION_TOKEN）。 */
	private String sessionToken;

	/** AWS 区域（必填，如 us-east-1，对应 SURE_AI_BEDROCK_REGION / AWS_REGION）。 */
	private String region;

	/** 默认模型 ID（可选，如 anthropic.claude-3-5-sonnet-20240620-v1:0）。 */
	private String model;

	/** 读超时占位（BedrockClient 当前未透传）。 */
	private Duration timeout;

	/** 最大重试次数占位（BedrockClient 当前未透传）。 */
	private Integer maxRetries;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getSessionToken() {
		return sessionToken;
	}

	public void setSessionToken(String sessionToken) {
		this.sessionToken = sessionToken;
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = region;
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

	public Integer getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(Integer maxRetries) {
		this.maxRetries = maxRetries;
	}
}
