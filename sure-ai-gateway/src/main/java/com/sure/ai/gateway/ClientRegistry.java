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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.Capability;

/**
 * 多供应商客户端注册表。
 *
 * <p>按 {@code (平台, 实例ID)} 二级存储，支持同平台多实例。每个注册项显式携带能力声明集合——
 * 由于 {@code capabilities()} 在 core 中是 {@code protected}、网关模块无法跨包反射调用，
 * 网关采用<b>注册时显式声明能力</b>的方案（任务书方案 A）：使用方注册时传入
 * {@link Capability} 集合，默认声明 {@code CHAT}/{@code CHAT_STREAM}。</p>
 *
 * <p>每个注册项附带健康状态：故障转移引擎在调用失败时通过
 * {@link #markUnhealthy} 把实例摘除一段时间，冷却期内 {@link #healthyCandidates} 不再返回它。</p>
 *
 * <p>线程安全：内部使用 {@link ConcurrentHashMap} 二级结构，注册/摘除/查询均可并发。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class ClientRegistry {

	/** 默认实例 ID。 */
	public static final String DEFAULT_INSTANCE = "default";

	/** 仅注册 AiClient 时的默认能力（对话核心能力）。 */
	private static final Set<Capability> DEFAULT_CAPABILITIES =
			Set.of(Capability.CHAT, Capability.CHAT_STREAM);

	/** platform → (instanceId → entry)。 */
	private final Map<String, Map<String, Entry>> registry = new ConcurrentHashMap<>();

	/**
	 * 注册一个客户端（默认实例，默认能力 CHAT/CHAT_STREAM，权重 1.0）。
	 *
	 * @param platform 平台名
	 * @param client   客户端
	 */
	public void register(String platform, AiClient client) {
		register(platform, DEFAULT_INSTANCE, client, DEFAULT_CAPABILITIES, 1.0d, null);
	}

	/**
	 * 注册一个客户端（默认能力，权重 1.0）。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 * @param client     客户端
	 */
	public void register(String platform, String instanceId, AiClient client) {
		register(platform, instanceId, client, DEFAULT_CAPABILITIES, 1.0d, null);
	}

	/**
	 * 注册一个客户端（自定义能力，权重 1.0）。
	 *
	 * @param platform     平台名
	 * @param client       客户端
	 * @param capabilities 声明的能力集合
	 */
	public void register(String platform, AiClient client, Set<Capability> capabilities) {
		register(platform, DEFAULT_INSTANCE, client, capabilities, 1.0d, null);
	}

	/**
	 * 注册一个客户端（自定义能力与默认模型，权重 1.0）。
	 *
	 * @param platform     平台名
	 * @param instanceId   实例 ID
	 * @param client       客户端
	 * @param capabilities 声明的能力集合
	 */
	public void register(String platform, String instanceId, AiClient client,
			Set<Capability> capabilities) {
		register(platform, instanceId, client, capabilities, 1.0d, null);
	}

	/**
	 * 全量注册。
	 *
	 * @param platform     平台名
	 * @param instanceId   实例 ID
	 * @param client       客户端
	 * @param capabilities 声明的能力集合
	 * @param weight       加权路由权重（&gt;0）
	 * @param defaultModel 该实例默认模型名（最低成本路由查价用），可为 null
	 */
	public void register(String platform, String instanceId, AiClient client,
			Set<Capability> capabilities, double weight, String defaultModel) {
		Objects.requireNonNull(platform, "platform must not be null");
		Objects.requireNonNull(instanceId, "instanceId must not be null");
		Objects.requireNonNull(client, "client must not be null");
		if (weight <= 0) {
			throw new IllegalArgumentException("weight must be > 0");
		}
		Entry entry = new Entry(client, capabilities, weight, defaultModel);
		registry.computeIfAbsent(platform, p -> new ConcurrentHashMap<>())
			.put(instanceId, entry);
	}

	/**
	 * 所有已注册客户端（含不健康实例）。
	 *
	 * @return 客户端列表
	 */
	public List<AiClient> all() {
		List<AiClient> out = new ArrayList<>();
		registry.values().forEach(m -> m.values().forEach(e -> out.add(e.client)));
		return out;
	}

	/**
	 * 某平台下的所有客户端（含不健康实例）。
	 *
	 * @param platform 平台名
	 * @return 客户端列表
	 */
	public List<AiClient> byPlatform(String platform) {
		Objects.requireNonNull(platform, "platform must not be null");
		Map<String, Entry> m = registry.get(platform);
		if (m == null) {
			return List.of();
		}
		List<AiClient> out = new ArrayList<>();
		m.values().forEach(e -> out.add(e.client));
		return out;
	}

	/**
	 * 过滤出声明了指定能力的所有客户端（含不健康实例）。
	 *
	 * @param cap 能力
	 * @return 客户端列表
	 */
	public List<AiClient> byCapability(Capability cap) {
		Objects.requireNonNull(cap, "cap must not be null");
		List<AiClient> out = new ArrayList<>();
		registry.values().forEach(m -> m.values().stream()
			.filter(e -> e.capabilities.contains(cap))
			.forEach(e -> out.add(e.client)));
		return out;
	}

	/**
	 * 精确查找客户端。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 * @return 客户端，未注册返回 null
	 */
	public AiClient byName(String platform, String instanceId) {
		Map<String, Entry> m = registry.get(platform);
		return m == null ? null : unwrap(m.get(instanceId));
	}

	/**
	 * 移除指定实例。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 */
	public void remove(String platform, String instanceId) {
		Map<String, Entry> m = registry.get(platform);
		if (m != null) {
			m.remove(instanceId);
			if (m.isEmpty()) {
				registry.remove(platform);
			}
		}
	}

	/**
	 * 移除某平台全部实例。
	 *
	 * @param platform 平台名
	 */
	public void removeAll(String platform) {
		registry.remove(platform);
	}

	/**
	 * 标记实例为不健康，在冷却期内不被路由选中。
	 *
	 * @param platform    平台名
	 * @param instanceId  实例 ID
	 * @param cooldownMs  冷却毫秒
	 */
	public void markUnhealthy(String platform, String instanceId, long cooldownMs) {
		Entry e = lookup(platform, instanceId);
		if (e != null) {
			e.unhealthyUntilMs = System.currentTimeMillis() + cooldownMs;
		}
	}

	/**
	 * 实例当前是否健康。
	 *
	 * @param platform   平台名
	 * @param instanceId 实例 ID
	 * @return 健康（或未注册）返回 true
	 */
	public boolean isHealthy(String platform, String instanceId) {
		Entry e = lookup(platform, instanceId);
		return e == null || e.healthy(System.currentTimeMillis());
	}

	/** 标记实例恢复健康（调用成功后由故障转移循环调用）。 */
	void markHealthy(String platform, String instanceId) {
		Entry e = lookup(platform, instanceId);
		if (e != null) {
			e.unhealthyUntilMs = 0L;
		}
	}

	/**
	 * 返回当前所有健康候选的快照（供路由策略选择）。
	 *
	 * @return 健康候选列表，可能为空
	 */
	List<ClientCandidate> healthyCandidates() {
		long now = System.currentTimeMillis();
		List<ClientCandidate> out = new ArrayList<>();
		registry.forEach((platform, m) -> m.forEach((instanceId, e) -> {
			if (e.healthy(now)) {
				out.add(new ClientCandidate(e.client, platform, instanceId,
						e.capabilities, e.weight, e.defaultModel));
			}
		}));
		return out;
	}

	private Entry lookup(String platform, String instanceId) {
		Map<String, Entry> m = registry.get(platform);
		return m == null ? null : m.get(instanceId);
	}

	private static AiClient unwrap(Entry e) {
		return e == null ? null : e.client;
	}

	/** 注册表内部条目：客户端 + 元数据 + 健康状态。 */
	private static final class Entry {
		/** 实际客户端。 */
		final AiClient client;

		/** 声明能力。 */
		final Set<Capability> capabilities;

		/** 权重。 */
		final double weight;

		/** 默认模型（最低成本查价用）。 */
		final String defaultModel;

		/** 不健康截止时间戳（ms epoch）；0 表示健康。volatile 保证可见性。 */
		volatile long unhealthyUntilMs;

		Entry(AiClient client, Set<Capability> capabilities, double weight, String defaultModel) {
			this.client = client;
			this.capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
			this.weight = weight;
			this.defaultModel = defaultModel;
		}

		boolean healthy(long now) {
			return now >= this.unhealthyUntilMs;
		}
	}
}
