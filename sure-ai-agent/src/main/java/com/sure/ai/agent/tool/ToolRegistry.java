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

package com.sure.ai.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;
import com.sure.tool.lang.Assert;

/**
 * 工具注册中心：维护「工具定义 → 执行处理器」映射，供编排器查询与回传 ChatRequest.tools。
 *
 * <p>线程安全：内部使用 {@link ConcurrentHashMap}，支持运行期动态注册/注销。
 * 同名重复注册会覆盖旧处理器（覆盖即语义，便于测试热替换）。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public final class ToolRegistry {

	/** 工具名 → 注册条目（定义 + 处理器）。 */
	private final Map<String, Entry> tools = new ConcurrentHashMap<>();

	/**
	 * 注册条目。
	 *
	 * @param spec    工具声明
	 * @param handler 执行处理器
	 */
	private record Entry(ToolSpec spec, ToolHandler handler) {
	}

	/**
	 * 注册工具（由 ToolFunction 直接构造 ToolSpec）。
	 *
	 * @param function 工具函数定义
	 * @param handler  执行处理器
	 */
	public void register(ToolFunction function, ToolHandler handler) {
		Assert.notNull(function, "function must not be null");
		Assert.notNull(handler, "handler must not be null");
		Assert.notBlank(function.name(), "function.name must not be blank");
		register(ToolSpec.of(function), handler);
	}

	/**
	 * 注册工具（直接给出 ToolSpec）。
	 *
	 * @param spec    工具声明
	 * @param handler 执行处理器
	 */
	public void register(ToolSpec spec, ToolHandler handler) {
		Assert.notNull(spec, "spec must not be null");
		Assert.notNull(spec.function(), "spec.function must not be null");
		Assert.notBlank(spec.function().name(), "tool name must not be blank");
		Assert.notNull(handler, "handler must not be null");
		this.tools.put(spec.function().name(), new Entry(spec, handler));
	}

	/**
	 * 注销工具。
	 *
	 * @param name 工具名
	 * @return 此前是否存在该工具
	 */
	public boolean unregister(String name) {
		return this.tools.remove(name) != null;
	}

	/**
	 * 按名查找处理器。
	 *
	 * @param name 工具名
	 * @return 处理器（不存在时 empty）
	 */
	public Optional<ToolHandler> getHandler(String name) {
		Entry e = this.tools.get(name);
		return Optional.ofNullable(e == null ? null : e.handler());
	}

	/**
	 * 工具函数定义查找（供参数校验使用）。
	 *
	 * @param name 工具名
	 * @return 函数定义（不存在时 empty）
	 */
	public Optional<ToolFunction> getFunction(String name) {
		Entry e = this.tools.get(name);
		return Optional.ofNullable(e == null ? null : e.spec().function());
	}

	/**
	 * 全部工具声明列表（顺序与注册顺序一致），供 {@code ChatRequest.tools} 使用。
	 *
	 * @return 工具声明列表
	 */
	public List<ToolSpec> getToolSpecs() {
		// 用 LinkedHashMap 快照保持注册顺序；ConcurrentHashMap 本身无序，故按键名排序保证确定性。
		List<ToolSpec> result = new ArrayList<>();
		this.tools.keySet().stream().sorted().forEach(n -> result.add(this.tools.get(n).spec()));
		return result;
	}

	/**
	 * 已注册工具数量。
	 *
	 * @return 数量
	 */
	public int size() {
		return this.tools.size();
	}

	/**
	 * 是否为空。
	 *
	 * @return true 表示无任何工具
	 */
	public boolean isEmpty() {
		return this.tools.isEmpty();
	}

	/**
	 * 清空全部工具。
	 */
	public void clear() {
		this.tools.clear();
	}

	/**
	 * 暴露内部映射（仅调试/测试用，返回快照副本，不影响线程安全语义）。
	 *
	 * @return 不可修改的 name→handler 视图
	 */
	Map<String, ToolHandler> snapshot() {
		Map<String, ToolHandler> m = new LinkedHashMap<>();
		this.tools.forEach((k, v) -> m.put(k, v.handler()));
		return Map.copyOf(m);
	}
}
