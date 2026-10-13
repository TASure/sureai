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

package com.sure.ai.gateway;

import com.sure.ai.client.AiClient;

/**
 * 默认健康探活：恒返回 {@code true}。
 *
 * <p>默认不额外探活——健康判定交给熔断器与调用失败摘除；生产环境可注入自定义 {@link HealthProbe}
 * （如真实 ping / 轻量探测请求）。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public final class DefaultHealthProbe implements HealthProbe {

	/** 构造默认探活。 */
	public DefaultHealthProbe() {
	}

	/**
	 * 恒返回健康。
	 *
	 * @param client     实际客户端（忽略）
	 * @param platform   平台名（忽略）
	 * @param instanceId 实例 ID（忽略）
	 * @return true
	 */
	@Override
	public boolean isHealthy(AiClient client, String platform, String instanceId) {
		return true;
	}
}
