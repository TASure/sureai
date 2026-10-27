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

package com.sure.ai.agent.tool.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.model.ToolFunction;
import com.sure.tool.lang.Assert;

/**
 * 工具市场（ToolCatalog）：在 {@link ToolRegistry}（执行注册中心）之上聚合
 * 「声明 + 处理器 + 元数据」，提供按标签 / 关键词 / 来源 / 风险级别的发现能力，
 * 并支持本地工具与 MCP 工具聚合、同名冲突策略、命名空间前缀，以及一键导出到
 * {@link ToolRegistry} 供 ReActAgent 使用。
 *
 * <p><b>与 ToolRegistry 的关系</b>：本类不改写 {@code ToolRegistry} 既有 API；
 * 可选择持有一个委托注册中心，注册成功后镜像写入（便于既有 Agent 直接使用），
 * 也可完全独立发现后再 {@link #exportTo(ToolRegistry)} 选择性导出。</p>
 *
 * <p><b>线程安全</b>：内部使用 {@link ConcurrentHashMap}；冲突解析在同一把锁内完成，
 * 注册 / 发现可并发进行。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class ToolCatalog {

	/** 默认冲突策略：覆盖（与 ToolRegistry 既有语义一致）。 */
	private static final ConflictStrategy DEFAULT_STRATEGY = ConflictStrategy.OVERWRITE;

	/** 条目表：工具名 → 市场条目。 */
	private final ConcurrentHashMap<String, ToolEntry> entries = new ConcurrentHashMap<>();

	/** 委托注册中心（可空；非空时注册成功后镜像写入）。 */
	private final ToolRegistry delegate;

	/** 默认冲突策略（可被单次调用显式策略覆盖）。 */
	private volatile ConflictStrategy defaultStrategy = DEFAULT_STRATEGY;

	/**
	 * 独立市场（不绑定委托注册中心）。
	 */
	public ToolCatalog() {
		this(null);
	}

	/**
	 * 绑定委托注册中心：注册成功后镜像写入。
	 *
	 * @param delegate 委托注册中心（可空表示不镜像）
	 */
	public ToolCatalog(ToolRegistry delegate) {
		this.delegate = delegate;
	}

	/**
	 * 设置默认冲突策略（fluent）。
	 *
	 * @param strategy 冲突策略（null 回退 OVERWRITE）
	 * @return this
	 */
	public ToolCatalog conflictStrategy(ConflictStrategy strategy) {
		this.defaultStrategy = strategy == null ? DEFAULT_STRATEGY : strategy;
		return this;
	}

	/**
	 * 注册本地工具（缺省元数据：LOCAL / LOW）。
	 *
	 * @param function 工具函数声明
	 * @param handler  执行处理器
	 * @return 实际落库的条目（RENAME 改名后可能与入参同名不同）
	 */
	public ToolEntry register(ToolFunction function, ToolHandler handler) {
		Assert.notNull(function, "function must not be null");
		Assert.notBlank(function.name(), "function.name must not be blank");
		ToolMetadata metadata = ToolMetadata.builder(function.name())
			.description(function.description() == null ? "" : function.description())
			.build();
		return register(new ToolEntry(function, handler, metadata));
	}

	/**
	 * 注册本地工具（显式元数据）。
	 *
	 * @param function 工具函数声明
	 * @param handler  执行处理器
	 * @param metadata 市场元数据
	 * @return 实际落库的条目
	 */
	public ToolEntry register(ToolFunction function, ToolHandler handler, ToolMetadata metadata) {
		Assert.notNull(function, "function must not be null");
		Assert.notNull(handler, "handler must not be null");
		Assert.notNull(metadata, "metadata must not be null");
		return register(new ToolEntry(function, handler, metadata));
	}

	/**
	 * 直接注册完整市场条目。
	 *
	 * @param entry 市场条目
	 * @return 实际落库的条目
	 */
	public ToolEntry register(ToolEntry entry) {
		return register(entry, null);
	}

	/**
	 * 直接注册完整市场条目（显式冲突策略，null 用默认）。
	 *
	 * @param entry    市场条目
	 * @param strategy 冲突策略（null 用默认）
	 * @return 实际落库的条目
	 */
	public ToolEntry register(ToolEntry entry, ConflictStrategy strategy) {
		Assert.notNull(entry, "entry must not be null");
		return put(entry, strategy);
	}

	/**
	 * 批量聚合 MCP 工具。
	 *
	 * <p>{@code namespace=true} 时按 {@code mcp.<server>.<tool>} 加命名空间前缀，
	 * 避免不同 MCP server 同名工具相撞；{@code namespace=false} 使用原始工具名，
	 * 随后由 {@code strategy} 决定冲突行为。元数据自动标注 {@link ToolSource#MCP}
	 * 与 {@code server} 服务端标识。</p>
	 *
	 * @param tools       待导入 MCP 工具列表（可空视为空）
	 * @param serverLabel MCP server 标识 / 命名空间（非空）
	 * @param strategy    冲突策略（null 用默认）
	 * @param namespace   是否加 {@code mcp.<server>.} 前缀
	 * @return 实际注册条目数
	 */
	public int registerMcpTools(List<ImportedMcpTool> tools, String serverLabel,
			ConflictStrategy strategy, boolean namespace) {
		Assert.notBlank(serverLabel, "serverLabel must not be blank");
		if (tools == null || tools.isEmpty()) {
			return 0;
		}
		String ns = sanitizeNamespace(serverLabel);
		int count = 0;
		for (ImportedMcpTool tool : tools) {
			Assert.notNull(tool, "imported tool must not be null");
			String rawName = tool.name();
			String finalName = namespace ? ("mcp." + ns + "." + rawName) : rawName;
			String schema = tool.schemaJson() == null ? "{}" : tool.schemaJson();
			String desc = tool.description() == null ? "" : tool.description();
			ToolFunction function = ToolFunction.of(finalName, desc, schema);
			ToolMetadata metadata = ToolMetadata.builder(finalName)
				.description(desc)
				.source(ToolSource.MCP)
				.server(serverLabel.trim())
				.riskLevel(RiskLevel.LOW)
				.build();
			put(new ToolEntry(function, tool.handler(), metadata), strategy);
			count++;
		}
		return count;
	}

	/**
	 * 按名查找条目。
	 *
	 * @param name 工具名
	 * @return 条目（不存在 empty）
	 */
	public Optional<ToolEntry> get(String name) {
		return Optional.ofNullable(name == null ? null : this.entries.get(name));
	}

	/**
	 * 全部条目快照（按名排序，确定顺序）。
	 *
	 * @return 条目列表
	 */
	public List<ToolEntry> list() {
		List<ToolEntry> result = new ArrayList<>(this.entries.values());
		result.sort(Comparator.comparing(ToolEntry::name));
		return result;
	}

	/**
	 * 按标签精确查找（大小写不敏感）。
	 *
	 * @param tag 标签
	 * @return 命中条目（按名排序）
	 */
	public List<ToolEntry> findByTag(String tag) {
		if (tag == null || tag.isBlank()) {
			return List.of();
		}
		String wanted = tag.trim().toLowerCase();
		return filter(e -> e.metadata().tags().contains(wanted));
	}

	/**
	 * 关键词查找：命中工具名、函数描述、元数据描述或任一标签（大小写不敏感包含）。
	 *
	 * @param keyword 关键词（空 / 空白返回空列表）
	 * @return 命中条目（按名排序）
	 */
	public List<ToolEntry> findByKeyword(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return List.of();
		}
		String kw = keyword.trim().toLowerCase();
		return filter(e -> {
			if (e.name().toLowerCase().contains(kw)) {
				return true;
			}
			String fnDesc = e.function().description();
			if (fnDesc != null && fnDesc.toLowerCase().contains(kw)) {
				return true;
			}
			String mdDesc = e.metadata().description();
			if (mdDesc != null && mdDesc.toLowerCase().contains(kw)) {
				return true;
			}
			for (String t : e.metadata().tags()) {
				if (t.contains(kw)) {
					return true;
				}
			}
			return false;
		});
	}

	/**
	 * 按来源过滤。
	 *
	 * @param source 来源类型（null 返回空）
	 * @return 命中条目（按名排序）
	 */
	public List<ToolEntry> filterBySource(ToolSource source) {
		if (source == null) {
			return List.of();
		}
		return filter(e -> e.metadata().source() == source);
	}

	/**
	 * 按风险级别过滤。
	 *
	 * @param level 风险级别（null 返回空）
	 * @return 命中条目（按名排序）
	 */
	public List<ToolEntry> filterByRiskLevel(RiskLevel level) {
		if (level == null) {
			return List.of();
		}
		return filter(e -> e.metadata().riskLevel() == level);
	}

	/**
	 * 注销条目。
	 *
	 * @param name 工具名
	 * @return 此前是否存在（存在时同步从委托注册中心注销）
	 */
	public boolean unregister(String name) {
		if (name == null) {
			return false;
		}
		ToolEntry removed = this.entries.remove(name);
		if (removed == null) {
			return false;
		}
		if (this.delegate != null) {
			this.delegate.unregister(name);
		}
		return true;
	}

	/** 条目数量。 */
	public int size() {
		return this.entries.size();
	}

	/** 是否为空。 */
	public boolean isEmpty() {
		return this.entries.isEmpty();
	}

	/**
	 * 清空市场（不清空委托注册中心，避免影响外部共享的全局注册中心）。
	 */
	public void clear() {
		this.entries.clear();
	}

	/**
	 * 把市场内全部条目导出到目标 {@link ToolRegistry}。
	 *
	 * @param target 目标注册中心
	 * @return 导出数量
	 */
	public int exportTo(ToolRegistry target) {
		return exportTo(target, e -> true);
	}

	/**
	 * 按谓词筛选后导出到目标 {@link ToolRegistry}。
	 *
	 * @param target    目标注册中心
	 * @param predicate 筛选条件（null 视为全选）
	 * @return 导出数量
	 */
	public int exportTo(ToolRegistry target, Predicate<ToolEntry> predicate) {
		Assert.notNull(target, "target must not be null");
		Predicate<ToolEntry> p = predicate == null ? e -> true : predicate;
		int count = 0;
		for (ToolEntry e : list()) {
			if (p.test(e)) {
				target.register(e.function(), e.handler());
				count++;
			}
		}
		return count;
	}

	/**
	 * 按名集合导出（任一未知名抛 {@link IllegalArgumentException}）。
	 *
	 * @param names  待导出工具名集合
	 * @param target 目标注册中心
	 * @return 导出数量
	 */
	public int exportNames(Set<String> names, ToolRegistry target) {
		Assert.notNull(names, "names must not be null");
		Assert.notNull(target, "target must not be null");
		List<String> missing = new ArrayList<>();
		for (String n : names) {
			if (!this.entries.containsKey(n)) {
				missing.add(n);
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("未知工具，无法导出: " + missing);
		}
		int count = 0;
		for (String n : names) {
			ToolEntry e = this.entries.get(n);
			target.register(e.function(), e.handler());
			count++;
		}
		return count;
	}

	/** 统一过滤 + 排序快照。 */
	private List<ToolEntry> filter(Predicate<ToolEntry> predicate) {
		List<ToolEntry> result = new ArrayList<>();
		for (ToolEntry e : this.entries.values()) {
			if (predicate.test(e)) {
				result.add(e);
			}
		}
		result.sort(Comparator.comparing(ToolEntry::name));
		return result;
	}

	/** 冲突解析 + 落库（同一把锁保证 RENAME / FAIL 原子）。 */
	private ToolEntry put(ToolEntry entry, ConflictStrategy strategy) {
		ConflictStrategy s = strategy == null ? this.defaultStrategy : strategy;
		synchronized (this) {
			String name = entry.name();
			ToolEntry existing = this.entries.get(name);
			if (existing != null) {
				switch (s) {
					case FAIL:
						throw new IllegalStateException("工具名冲突，拒绝注册: " + name);
					case RENAME:
						entry = rename(entry, uniqueName(name));
						break;
					case OVERWRITE:
						break;
					default:
						break;
				}
			}
			this.entries.put(entry.name(), entry);
			if (this.delegate != null) {
				this.delegate.register(entry.function(), entry.handler());
			}
			return entry;
		}
	}

	/** 在表内生成不冲突的唯一名。 */
	private String uniqueName(String base) {
		String candidate = base;
		int i = 1;
		while (this.entries.containsKey(candidate)) {
			candidate = base + "__" + i;
			i++;
		}
		return candidate;
	}

	/** 生成改名后的新条目（函数名与元数据名同步替换）。 */
	private static ToolEntry rename(ToolEntry entry, String newName) {
		ToolFunction old = entry.function();
		ToolFunction renamed = ToolFunction.of(newName, old.description(), old.parameters());
		ToolMetadata metadata = entry.metadata().toBuilder().name(newName).build();
		return new ToolEntry(renamed, entry.handler(), metadata);
	}

	/** 命名空间清洗：小写，非 [a-z0-9_.-] 替换为下划线。 */
	private static String sanitizeNamespace(String label) {
		String lower = label.trim().toLowerCase();
		StringBuilder sb = new StringBuilder(lower.length());
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-') {
				sb.append(c);
			} else {
				sb.append('_');
			}
		}
		return sb.toString();
	}

}
