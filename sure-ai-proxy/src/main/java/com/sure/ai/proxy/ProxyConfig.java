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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 代理配置：仅用 {@link Properties} 加载，不引入任何配置库。
 *
 * <p>配置文件只描述代理自身参数（端口、虚拟密钥、默认模型、对外暴露的模型列表），
 * 不配置任何平台 Client——平台 Client 由使用方编程式注册到 {@code ClientRegistry}。
 * 配置项示例：</p>
 * <pre>
 * proxy.port=8080
 * proxy.default.model=gpt-4o
 * proxy.models=gpt-4o,gpt-3.5-turbo,text-embedding-3-small
 * proxy.key.sk-tenant1-key=tenant1
 * proxy.key.sk-tenant2-key=tenant2
 * </pre>
 *
 * <p>不可变；通过 {@link #defaults()} / {@link #fromProperties(Properties)} /
 * {@link #load(Path)} 构造。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class ProxyConfig {

	/** 默认监听端口。 */
	public static final int DEFAULT_PORT = 8080;

	/** 默认模型名（请求未携带 model 时兜底）。 */
	public static final String DEFAULT_MODEL = "gpt-4o";

	/** 端口配置键。 */
	static final String KEY_PORT = "proxy.port";

	/** 默认模型配置键。 */
	static final String KEY_DEFAULT_MODEL = "proxy.default.model";

	/** 对外暴露模型列表配置键（逗号分隔）。 */
	static final String KEY_MODELS = "proxy.models";

	/** 虚拟密钥配置前缀：{@code proxy.key.<virtualKey>=tenantId}。 */
	static final String KEY_PREFIX_KEY = "proxy.key.";

	/** 监听端口。 */
	private final int port;

	/** 默认模型名。 */
	private final String defaultModel;

	/** 对外暴露的模型列表（/v1/models 返回）。 */
	private final List<String> exposedModels;

	/** 虚拟密钥 -> 租户 ID。 */
	private final Map<String, String> keyToTenant;

	private ProxyConfig(int port, String defaultModel, List<String> exposedModels,
			Map<String, String> keyToTenant) {
		this.port = port;
		this.defaultModel = defaultModel;
		this.exposedModels = List.copyOf(exposedModels);
		this.keyToTenant = Collections.unmodifiableMap(new TreeMap<>(keyToTenant));
	}

	/**
	 * 默认配置：端口 8080、默认模型 gpt-4o、无虚拟密钥、仅暴露默认模型。
	 *
	 * @return 默认配置
	 */
	public static ProxyConfig defaults() {
		return new ProxyConfig(DEFAULT_PORT, DEFAULT_MODEL, List.of(DEFAULT_MODEL), Map.of());
	}

	/**
	 * 从 {@link Properties} 加载配置。
	 *
	 * @param props 属性集
	 * @return 代理配置
	 */
	public static ProxyConfig fromProperties(Properties props) {
		int port = parsePort(props.getProperty(KEY_PORT), DEFAULT_PORT);
		String defaultModel = props.getProperty(KEY_DEFAULT_MODEL, DEFAULT_MODEL).trim();
		List<String> models = parseModels(props.getProperty(KEY_MODELS), defaultModel);
		Map<String, String> keys = new TreeMap<>();
		for (String name : props.stringPropertyNames()) {
			if (name.startsWith(KEY_PREFIX_KEY)) {
				String virtualKey = name.substring(KEY_PREFIX_KEY.length()).trim();
				String tenant = props.getProperty(name).trim();
				if (!virtualKey.isEmpty() && !tenant.isEmpty()) {
					keys.put(virtualKey, tenant);
				}
			}
		}
		return new ProxyConfig(port, defaultModel, models, keys);
	}

	/**
	 * 从 properties 文件路径加载。
	 *
	 * @param path 配置文件路径
	 * @return 代理配置
	 * @throws IOException 读取失败
	 */
	public static ProxyConfig load(Path path) throws IOException {
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(path)) {
			props.load(in);
		}
		return fromProperties(props);
	}

	/** 解析端口，非法值回退默认。 */
	private static int parsePort(String raw, int fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			int p = Integer.parseInt(raw.trim());
			return p >= 0 && p < 65536 ? p : fallback;
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	/** 解析逗号分隔模型列表，空则回退默认模型。 */
	private static List<String> parseModels(String raw, String defaultModel) {
		List<String> out = new ArrayList<>();
		if (raw != null && !raw.isBlank()) {
			for (String s : raw.split(",")) {
				String t = s.trim();
				if (!t.isEmpty()) {
					out.add(t);
				}
			}
		}
		if (out.isEmpty()) {
			out.add(defaultModel);
		}
		return out;
	}

	/**
	 * 监听端口。
	 *
	 * @return 端口
	 */
	public int port() {
		return this.port;
	}

	/**
	 * 默认模型名。
	 *
	 * @return 默认模型
	 */
	public String defaultModel() {
		return this.defaultModel;
	}

	/**
	 * 对外暴露的模型列表（不可变）。
	 *
	 * @return 模型列表
	 */
	public List<String> exposedModels() {
		return this.exposedModels;
	}

	/**
	 * 虚拟密钥 -> 租户 ID 映射（不可变）。
	 *
	 * @return 密钥映射
	 */
	public Map<String, String> keyToTenant() {
		return this.keyToTenant;
	}
}
