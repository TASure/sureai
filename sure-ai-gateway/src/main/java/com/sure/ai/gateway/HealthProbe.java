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
 * 健康探活 SPI：判断某注册实例当前是否健康。
 *
 * <p>返回 {@code false} 表示不健康。实现须轻量快速（不可阻塞太久）；运行时异常由调用方捕获并视为不健康。</p>
 *
 * @author sureai
 * @since 2.7.0
 */
public interface HealthProbe {

	/**
	 * 探活一次。
	 *
	 * @param client     实际客户端
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 * @return true=健康，false=不健康
	 */
	boolean isHealthy(AiClient client, String platform, String instanceId);
}
