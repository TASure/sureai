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

/**
 * AWS Bedrock 平台的「可跨构建期→运行期记录」数据载体。
 *
 * <p>Bedrock 不走单 API Key，而使用 SigV4 四元组凭证（accessKey/secretKey/sessionToken/region）+ 默认 modelId，
 * 因此独立于 {@link ClientSpec}。全部为 String，满足 Quarkus 字节码录制的可录制要求。</p>
 */
public class BedrockSpec {

	/** AWS Access Key ID（必填）。 */
	private String accessKey;

	/** AWS Secret Access Key（必填）。 */
	private String secretKey;

	/** 临时会话令牌（可空）。 */
	private String sessionToken;

	/** AWS 区域（必填，如 us-east-1）。 */
	private String region;

	/** 默认模型 ID（可空）。 */
	private String model;

	/** 无参构造器（Quarkus 录制要求）。 */
	public BedrockSpec() {
	}

	public String getAccessKey() {
		return this.accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return this.secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getSessionToken() {
		return this.sessionToken;
	}

	public void setSessionToken(String sessionToken) {
		this.sessionToken = sessionToken;
	}

	public String getRegion() {
		return this.region;
	}

	public void setRegion(String region) {
		this.region = region;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}
}
