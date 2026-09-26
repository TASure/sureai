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

import java.util.Map;

import com.sure.ai.client.Capability;

/**
 * 路由上下文：一次调用在网关层的路由决策输入。
 *
 * <p>由 {@link GatewayClient} 在每次调用前从请求模型（如 {@code ChatRequest}）构建，
 * 供路由策略判断。不可变。</p>
 *
 * <p><b>显式路由字段</b>：优先读取 {@code extra} 中的 {@code "platform"}（字符串）；
 * 未设置时回退到模型名前缀——形如 {@code "openai:gpt-4o"} 的模型名会把 {@code "openai"}
 * 解析为目标平台。这两个入口使调用方可在不改变业务请求结构的前提下强制指定平台。</p>
 *
 * @param method    被调用的方法名（如 {@code "chat"} / {@code "chatStream"}）
 * @param capability 本次调用所需能力
 * @param model     模型名（可能带 {@code platform:} 前缀）
 * @param extra     请求透传字段（原样来自 ChatRequest.extra()），不为 null
 * @param tenantId  租户 ID（为后续多租户配额预留），可为 null
 * @author sureai
 * @since 1.6.0
 */
public record RequestContext(String method, Capability capability, String model,
		Map<String, Object> extra, String tenantId) {

	/** extra 中显式指定目标平台的键。 */
	public static final String EXTRA_PLATFORM = "platform";

	/** extra 中租户 ID 的键。 */
	public static final String EXTRA_TENANT_ID = "tenantId";

	/**
	 * 紧凑构造器：防御性拷贝 extra。
	 */
	public RequestContext {
		extra = extra == null ? Map.of() : Map.copyOf(extra);
	}

	/**
	 * 解析调用方显式指定的目标平台。
	 *
	 * <p>顺序：① {@code extra["platform"]}；② 模型名中 {@code ':'} 之前的前缀。
	 * 两者都没有时返回 null（交由策略自由选择）。</p>
	 *
	 * @return 显式平台名，或 null
	 */
	public String explicitPlatform() {
		Object p = this.extra.get(EXTRA_PLATFORM);
		if (p instanceof String s && !s.isBlank()) {
			return s;
		}
		if (this.model != null) {
			int idx = this.model.indexOf(':');
			if (idx > 0) {
				String prefix = this.model.substring(0, idx);
				if (!prefix.isBlank()) {
					return prefix;
				}
			}
		}
		return null;
	}

	/**
	 * 去除模型名的平台前缀，得到真正发给下游平台的裸模型名。
	 *
	 * @return 裸模型名
	 */
	public String bareModel() {
		if (this.model == null) {
			return null;
		}
		int idx = this.model.indexOf(':');
		return idx > 0 ? this.model.substring(idx + 1) : this.model;
	}
}
