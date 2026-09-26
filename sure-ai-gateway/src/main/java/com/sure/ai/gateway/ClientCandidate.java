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

import java.util.Set;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.Capability;

/**
 * 路由候选：注册表中一个可用的 {@link AiClient} 实例及其路由元数据。
 *
 * <p>路由策略不再面对裸 {@link AiClient}，而是面对携带平台名、实例 ID、能力声明、权重与
 * 默认模型的候选描述符，从而支持加权 / 最低成本 / 能力过滤等策略。不可变。</p>
 *
 * @param client       实际调用的客户端
 * @param platform     平台名（如 {@code "openai"}）
 * @param instanceId   同平台内的实例 ID
 * @param capabilities 该实例声明支持的能力集合（注册时显式传入，避免反射调用
 *                     {@code protected capabilities()}）
 * @param weight       权重（加权路由用，默认 1.0）
 * @param defaultModel 该实例对未显式指定模型时默认使用的模型名（最低成本路由查价用），可为 null
 * @author sureai
 * @since 1.6.0
 */
public record ClientCandidate(AiClient client, String platform, String instanceId,
		Set<Capability> capabilities, double weight, String defaultModel) {

	/**
	 * 全参构造器（防御性拷贝能力集合为不可变）。
	 */
	public ClientCandidate {
		capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
	}

	/**
	 * 是否声明了指定能力。
	 *
	 * @param c 能力
	 * @return 声明了返回 true
	 */
	public boolean has(Capability c) {
		return this.capabilities.contains(c);
	}
}
