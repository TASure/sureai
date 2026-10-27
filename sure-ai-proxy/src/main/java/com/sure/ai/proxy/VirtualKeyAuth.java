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

package com.sure.ai.proxy;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虚拟密钥鉴权：把 {@code Authorization: Bearer <virtualKey>} 映射到租户 ID。
 *
 * <p>虚拟密钥与真实平台 key 解耦——调用方只需持有代理签发的虚拟 key，代理再凭
 * {@link ProxyConfig} 注册的下游 Client 访问真实平台。解析出的 tenantId 会写入
 * {@code ChatRequest.extra["tenantId"]}，供网关层做多租户配额。</p>
 *
 * <p>线程安全：内部使用 {@link ConcurrentHashMap}，注册与鉴权可并发。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class VirtualKeyAuth {

	/** Bearer 方案前缀（不区分大小写比较）。 */
	private static final String BEARER_PREFIX = "bearer ";

	/** virtualKey -> tenantId。 */
	private final Map<String, String> keyToTenant = new ConcurrentHashMap<>();

	/**
	 * 从配置的密钥映射批量构建。
	 *
	 * @param keyToTenant 虚拟密钥 -> 租户 ID
	 */
	public VirtualKeyAuth(Map<String, String> keyToTenant) {
		Objects.requireNonNull(keyToTenant, "keyToTenant must not be null");
		this.keyToTenant.putAll(keyToTenant);
	}

	/**
	 * 注册一个虚拟密钥。
	 *
	 * @param virtualKey 虚拟密钥
	 * @param tenantId   租户 ID
	 */
	public void registerKey(String virtualKey, String tenantId) {
		Objects.requireNonNull(virtualKey, "virtualKey must not be null");
		Objects.requireNonNull(tenantId, "tenantId must not be null");
		this.keyToTenant.put(virtualKey, tenantId);
	}

	/**
	 * 从 Authorization 头解析并鉴权。
	 *
	 * <p>返回值约定：返回非空 tenantId 表示鉴权通过；返回 {@code null} 表示未提供头、
	 * 缺少 {@code Bearer} 前缀或密钥未注册（调用方应回 401）。</p>
	 *
	 * @param authorizationHeader 请求头 {@code Authorization} 原值，可为 null
	 * @return 租户 ID，鉴权失败返回 null
	 */
	public String authenticate(String authorizationHeader) {
		if (authorizationHeader == null || authorizationHeader.isBlank()) {
			return null;
		}
		String header = authorizationHeader.trim();
		if (!header.toLowerCase().startsWith(BEARER_PREFIX)) {
			return null;
		}
		String token = header.substring(BEARER_PREFIX.length()).trim();
		if (token.isEmpty()) {
			return null;
		}
		return this.keyToTenant.get(token);
	}

	/**
	 * 当前已注册密钥数。
	 *
	 * @return 密钥数
	 */
	public int size() {
		return this.keyToTenant.size();
	}
}
