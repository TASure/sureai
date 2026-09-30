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

package com.sure.ai.cli;

import com.sure.ai.client.AiClient;

/**
 * 单个平台的静态描述：名称、展示名、默认模型、凭证环境变量与客户端构造方式。
 *
 * @param name              平台标识（命令行 --provider 取值，小写）
 * @param displayName       中文展示名
 * @param defaultModel      默认对话模型
 * @param defaultEmbeddingModel 默认嵌入模型（用于 RAG），不支持嵌入时为 null
 * @param envApiKey         API Key 环境变量名，无需 Key（本地 server）时为 null
 * @param requiresApiKey    是否必须提供 API Key
 * @param constructor       由 (apiKey, baseUrl, env) 构造客户端
 * @author sureai
 * @since 2.0.0
 */
public record ProviderDescriptor(String name, String displayName, String defaultModel,
		String defaultEmbeddingModel, String envApiKey, boolean requiresApiKey,
		Constructor constructor) {

	/**
	 * 平台客户端构造函数式接口。
	 */
	@FunctionalInterface
	public interface Constructor {

		/**
		 * 构造客户端。
		 *
		 * @param apiKey  已解析的 API Key（可能为占位 dummy）
		 * @param baseUrl 已解析的网关覆盖，可空
		 * @param env     环境变量源（Bedrock 等读取 AWS 凭证用）
		 * @return 客户端
		 */
		AiClient build(String apiKey, String baseUrl, Environment env);
	}
}
