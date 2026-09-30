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
 * 默认客户端工厂：按 {@link ProviderRegistry} 解析平台与凭证，构造真实平台客户端。
 *
 * <p>凭证解析优先级：命令行 {@code --api-key} &gt; 平台对应环境变量；
 * 无需 Key 的本地平台（ollama/llamacpp）在缺省时使用占位 {@code dummy}。</p>
 *
 * @author sureai
 * @since 2.0.0
 */
public final class DefaultClientFactory implements ClientFactory {

	/** 本地 server 无鉴权时的占位 Key。 */
	private static final String DUMMY_KEY = "dummy";

	private final Environment env;

	/**
	 * 构造工厂。
	 *
	 * @param env 环境变量源
	 */
	public DefaultClientFactory(Environment env) {
		this.env = env;
	}

	@Override
	public Prepared prepare(GlobalOptions global) {
		ProviderDescriptor descriptor = ProviderRegistry.forName(global.provider());
		if (descriptor == null) {
			throw new CliException(CliException.EXIT_USAGE,
				"未知平台: " + global.provider() + "（可用平台见 sureai list）");
		}
		String apiKey = resolveApiKey(global, descriptor);
		AiClient client = descriptor.constructor().build(apiKey, global.baseUrl(), env);
		String model = (global.model() != null && !global.model().isBlank())
			? global.model() : descriptor.defaultModel();
		return new Prepared(descriptor, client, model);
	}

	/** 解析 API Key：命令行优先，其次环境变量；必须 Key 缺失时报错。 */
	private String resolveApiKey(GlobalOptions global, ProviderDescriptor descriptor) {
		if (global.apiKey() != null && !global.apiKey().isBlank()) {
			return global.apiKey();
		}
		if (!descriptor.requiresApiKey()) {
			return DUMMY_KEY;
		}
		String fromEnv = env.getenv(descriptor.envApiKey());
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv;
		}
		throw new CliException(CliException.EXIT_USAGE,
			"平台 " + descriptor.name() + " 未配置 API Key：请用 --api-key 传入，"
			+ "或设置环境变量 " + descriptor.envApiKey());
	}
}
