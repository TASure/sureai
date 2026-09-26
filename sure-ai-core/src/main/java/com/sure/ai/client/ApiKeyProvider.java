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

import java.util.List;

/**
 * API Key 提供者：抽象一个可轮转的密钥池。
 *
 * <p>核心定位是<b>与具体平台/客户端解耦</b>的密钥管理原语——它只关心“下一个该用哪个 key、
 * 哪个 key 坏了要暂时摘除”，不关心 key 最终如何注入某个具体平台客户端。这样它可以被任何
 * 需要多 key 轮转的上层（例如网关的密钥轮转装饰器）复用，而不必依赖网关或任何平台模块。</p>
 *
 * <p>典型用法：上层持有本接口引用，每次调用前通过 {@link #currentKey()} 取当前 key 构造/重建
 * 下游客户端；当调用因 401（鉴权失败）或 429（被平台限流）失败时，调用 {@link #markBad(String)}
 * 把该 key 临时拉黑，再用 {@link #nextKey()} 切到下一个可用 key 重试。</p>
 *
 * <p>实现必须线程安全。</p>
 *
 * @author sureai
 * @since 1.6.0
 */
public interface ApiKeyProvider {

	/**
	 * 当前应使用的 key（不会返回处于冷却期内的坏 key，除非全部 key 都在冷却期）。
	 *
	 * <p>连续调用而不调用 {@link #nextKey()} 时应稳定返回同一个 key。</p>
	 *
	 * @return 当前可用 key，永不为 null（池非空时）
	 */
	String currentKey();

	/**
	 * 切换到池中的下一个可用 key 并返回。
	 *
	 * <p>会跳过处于冷却期内的坏 key。全部 key 都在冷却期时，仍返回轮询指针指向的那个 key
	 * （由上层承担真实失败并聚合异常）。</p>
	 *
	 * @return 下一个可用 key
	 */
	String nextKey();

	/**
	 * 标记某个 key 为坏（如 401 鉴权失败 / 429 被平台限流），在冷却期内不再被选中。
	 *
	 * <p>冷却期由实现决定；过期后该 key 自动重新可用。标记一个不存在于池中的 key 是无操作。</p>
	 *
	 * @param key 坏 key
	 */
	void markBad(String key);

	/**
	 * 池中的全部 key（注册顺序快照，含坏 key）。
	 *
	 * @return 不可变 key 列表
	 */
	List<String> allKeys();

	/**
	 * 池中 key 数量。
	 *
	 * @return key 数量
	 */
	int size();
}
