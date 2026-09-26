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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.sure.tool.lang.Assert;

/**
 * 租户管理器：维护“虚拟密钥 → 租户”映射与“租户 → 配额配置”映射。
 *
 * <p>虚拟密钥（virtual key）是对外暴露给调用方的脱敏 key——调用方持有的不是真实平台 key，
 * 而是由本网关签发的虚拟 key；网关在入口处用 {@link #resolveTenant(String)} 把它解析回
 * 内部租户 ID，再做配额/预算管控。真实平台 key 的轮转由 {@link com.sure.ai.client.ApiKeyProvider}
 * 负责，与本类解耦。</p>
 *
 * <p>未配置 {@link TenantConfig} 的租户视为不限额（{@link #configFor(String)} 返回 {@code null}，
 * 由 {@link BudgetEnforcer} 按“不限制”处理）。线程安全。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class TenantManager {

	/** 虚拟密钥 → 租户 ID。 */
	private final ConcurrentMap<String, String> virtualKeys = new ConcurrentHashMap<>();

	/** 租户 ID → 配额配置。 */
	private final ConcurrentMap<String, TenantConfig> tenantConfigs = new ConcurrentHashMap<>();

	/**
	 * 注册虚拟密钥到租户的映射。
	 *
	 * @param virtualKey 虚拟密钥
	 * @param tenantId   租户 ID
	 */
	public void registerVirtualKey(String virtualKey, String tenantId) {
		Assert.notBlank(virtualKey, "virtualKey must not be blank");
		Assert.notBlank(tenantId, "tenantId must not be blank");
		this.virtualKeys.put(virtualKey, tenantId);
	}

	/**
	 * 解析虚拟密钥对应的租户。
	 *
	 * @param virtualKey 虚拟密钥
	 * @return 租户 ID；未注册返回 null
	 */
	public String resolveTenant(String virtualKey) {
		if (virtualKey == null) {
			return null;
		}
		return this.virtualKeys.get(virtualKey);
	}

	/**
	 * 移除一个虚拟密钥。
	 *
	 * @param virtualKey 虚拟密钥
	 */
	public void removeVirtualKey(String virtualKey) {
		if (virtualKey != null) {
			this.virtualKeys.remove(virtualKey);
		}
	}

	/**
	 * 配置租户配额。
	 *
	 * @param tenantId 租户 ID
	 * @param config   配额配置
	 */
	public void configureTenant(String tenantId, TenantConfig config) {
		Assert.notBlank(tenantId, "tenantId must not be blank");
		Assert.notNull(config, "config must not be null");
		this.tenantConfigs.put(tenantId, config);
	}

	/**
	 * 查询租户配额配置。
	 *
	 * @param tenantId 租户 ID
	 * @return 配置；未配置返回 null
	 */
	public TenantConfig configFor(String tenantId) {
		if (tenantId == null) {
			return null;
		}
		return this.tenantConfigs.get(tenantId);
	}
}
