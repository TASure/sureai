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

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;

/**
 * AWS Bedrock 平台配置组（对应 {@code sure.ai.bedrock.*}）。
 *
 * <p>Bedrock 不走单 API Key，而使用 SigV4 四元组凭证（access-key/secret-key/session-token/region），
 * 因此不能复用通用 {@link PlatformConfig}，独立成组。装配条件：{@code access-key} 存在即注册 {@code BedrockClient}。</p>
 */
@ConfigGroup
public interface BedrockGroup {

	/** AWS Access Key ID（必填，存在即装配）。 */
	Optional<String> accessKey();

	/** AWS Secret Access Key（必填）。 */
	Optional<String> secretKey();

	/** 临时会话令牌（可空）。 */
	Optional<String> sessionToken();

	/** AWS 区域（必填，如 us-east-1）。 */
	Optional<String> region();

	/** 默认模型 ID（可空）。 */
	Optional<String> model();
}
