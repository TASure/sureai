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

package com.sure.ai.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.sure.tool.lang.Assert;

/**
 * 轮询（Round-Robin）密钥池：用一个原子指针在注册的 key 列表上轮流取 key。
 *
 * <p>坏 key 通过 {@link #markBad(String)} 记入 {@code key → badUntilEpochMs} 表；
 * 在冷却期内 {@link #currentKey()} / {@link #nextKey()} 会自动跳过它，冷却到期后自动恢复。</p>
 *
 * <h2>线程安全</h2>
 * <p>轮询指针推进与坏 key 选择通过 {@code synchronized} 串行化（密钥池规模通常很小、
 * 调用频次远低于每秒请求量，锁竞争可忽略）；坏 key 表本身是 {@link ConcurrentHashMap}，
 * 标记与读取并发安全。</p>
 *
 * <h2>可注入时钟</h2>
 * <p>冷却期是否到期由一个 {@link LongSupplier}（返回 epoch 毫秒）判定，默认
 * {@link System#currentTimeMillis()}。测试时可注入伪时钟以精确验证冷却到期逻辑。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public final class RoundRobinApiKeyProvider implements ApiKeyProvider {

	/** 默认坏 key 冷却时间（毫秒）。 */
	public static final long DEFAULT_BAD_KEY_COOLDOWN_MS = 60_000L;

	/** 全部 key（注册顺序，不可变视图）。 */
	private final List<String> keys;

	/** 坏 key 冷却时长（毫秒）。 */
	private final long badKeyCooldownMs;

	/** 时钟：返回当前 epoch 毫秒。 */
	private final LongSupplier clock;

	/** 轮询指针：指向最近一次返回的 key 下标。 */
	private int index;

	/** key → 坏状态截止 epoch 毫秒；未在表中即视为健康。 */
	private final ConcurrentHashMap<String, Long> badUntil = new ConcurrentHashMap<>();

	/**
	 * 全参构造器。
	 *
	 * @param keys              密钥列表（至少 1 个，不允许 null/空串）
	 * @param badKeyCooldownMs  坏 key 冷却毫秒（&gt;0）
	 * @param clock             时钟（返回 epoch 毫秒），用于判定冷却到期
	 */
	public RoundRobinApiKeyProvider(List<String> keys, long badKeyCooldownMs, LongSupplier clock) {
		Objects.requireNonNull(keys, "keys must not be null");
		Assert.notEmpty(keys, "keys must not be empty");
		for (String k : keys) {
			Assert.notBlank(k, "apiKey must not be blank");
		}
		if (badKeyCooldownMs <= 0) {
			throw new IllegalArgumentException("badKeyCooldownMs must be > 0");
		}
		Objects.requireNonNull(clock, "clock must not be null");
		this.keys = List.copyOf(keys);
		this.badKeyCooldownMs = badKeyCooldownMs;
		this.clock = clock;
	}

	/**
	 * 构造器：默认冷却 60s，系统时钟。
	 *
	 * @param keys 密钥列表
	 */
	public RoundRobinApiKeyProvider(List<String> keys) {
		this(keys, DEFAULT_BAD_KEY_COOLDOWN_MS, System::currentTimeMillis);
	}

	/**
	 * 构造器：自定义冷却，系统时钟。
	 *
	 * @param keys             密钥列表
	 * @param badKeyCooldownMs 坏 key 冷却毫秒（&gt;0）
	 */
	public RoundRobinApiKeyProvider(List<String> keys, long badKeyCooldownMs) {
		this(keys, badKeyCooldownMs, System::currentTimeMillis);
	}

	@Override
	public String currentKey() {
		return pick(false);
	}

	@Override
	public synchronized String nextKey() {
		return pick(true);
	}

	@Override
	public void markBad(String key) {
		if (key == null || !this.keys.contains(key)) {
			return;
		}
		this.badUntil.put(key, this.clock.getAsLong() + this.badKeyCooldownMs);
	}

	@Override
	public List<String> allKeys() {
		return new ArrayList<>(this.keys);
	}

	@Override
	public int size() {
		return this.keys.size();
	}

	/**
	 * 选取 key。
	 *
	 * @param advance 是否先把指针前移一位（nextKey=true / currentKey=false）
	 * @return 选中的 key
	 */
	private synchronized String pick(boolean advance) {
		int n = this.keys.size();
		long now = this.clock.getAsLong();
		int start = advance ? this.index + 1 : this.index;
		// 从 start 起最多扫 n 个槽位，找第一个未在冷却期内的 key
		for (int off = 0; off < n; off++) {
			int pos = Math.floorMod(start + off, n);
			String candidate = this.keys.get(pos);
			if (!isBad(candidate, now)) {
				this.index = pos;
				return candidate;
			}
		}
		// 全部 key 都在冷却期：返回指针指向的那个，由上层承担真实失败
		int pos = Math.floorMod(start, n);
		this.index = pos;
		return this.keys.get(pos);
	}

	/**
	 * 判定 key 当前是否处于冷却期。
	 *
	 * @param key key
	 * @param now 当前 epoch 毫秒
	 * @return 在冷却期返回 true
	 */
	private boolean isBad(String key, long now) {
		Long until = this.badUntil.get(key);
		return until != null && now < until;
	}
}
