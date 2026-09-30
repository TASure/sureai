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

/**
 * 全局选项（出现在子命令之前）。
 *
 * <p>{@code apiKey} 为 null 表示未显式传入，需回退环境变量解析；
 * {@code model}/{@code baseUrl} 为 null 表示使用平台默认值。</p>
 *
 * @param provider  平台名（不区分大小写），默认 openai
 * @param apiKey    显式传入的 API Key，可空
 * @param model     显式指定的模型名，可空
 * @param baseUrl   显式覆盖的网关地址，可空
 * @author sureai
 * @since 2.0.0
 */
public record GlobalOptions(String provider, String apiKey, String model, String baseUrl) {

	/** 默认平台。 */
	public static final String DEFAULT_PROVIDER = "openai";

	/**
	 * 归一化构造：provider 空白时回退 openai。
	 */
	public GlobalOptions {
		provider = (provider == null || provider.isBlank()) ? DEFAULT_PROVIDER : provider.toLowerCase();
	}
}
