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

package com.sure.ai.rag.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sure.tool.lang.Assert;

/**
 * 可变知识图谱：持有实体（id→{@link GraphEntity}）与关系列表。
 *
 * <p>实体名规范化策略：{@link #normalizeName(String)} 去除首尾空白、内部所有空白并转小写，
 * 因此 {@code "Beijing"}、{@code " beijing "}、{@code "Bei jing"} 会被合并为同一实体。
 * 同名实体重复加入时按 id 合并，保留首次展示名，元数据取并集。</p>
 *
 * <p>线程安全：底层使用 {@link ConcurrentHashMap} 与 {@link CopyOnWriteArrayList}；
 * 复合写操作（{@link #addEntity}、{@link #addRelation}）在监视器锁内完成读-改-写，
 * 读操作在并发集合上无锁进行并返回快照/不可变视图。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class KnowledgeGraph {

	/** 元数据键：来源文档 id。 */
	public static final String META_SOURCE_DOC_ID = "sourceDocId";

	private final Map<String, GraphEntity> entities = new ConcurrentHashMap<>();
	private final List<GraphRelation> relations = new CopyOnWriteArrayList<>();

	/**
	 * 规范化实体名：去首尾空白、去除内部所有空白、转小写。
	 *
	 * @param raw 原始实体名，可空
	 * @return 规范化后的实体 id；入参为 null 时返回空串
	 */
	public static String normalizeName(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.trim().toLowerCase().replaceAll("\\s+", "");
	}

	/**
	 * 加入实体；同名（规范化后）实体合并元数据并返回规范化实体。
	 *
	 * @param name     实体名，不可为 null（规范化后不能为空）
	 * @param type     实体类别，可空
	 * @param metadata 附加元数据，可空
	 * @return 规范化后的实体
	 */
	public synchronized GraphEntity addEntity(String name, String type, Map<String, String> metadata) {
		Assert.notNull(name, "name 不能为 null");
		String id = normalizeName(name);
		Assert.isTrue(!id.isEmpty(), "实体名规范化后不能为空");
		Map<String, String> meta = metadata == null ? Collections.emptyMap() : metadata;

		GraphEntity existing = this.entities.get(id);
		if (existing != null) {
			Map<String, String> merged = new LinkedHashMap<>(existing.metadata());
			merged.putAll(meta);
			String resolvedType = existing.type() == null ? type : existing.type();
			GraphEntity updated = new GraphEntity(id, existing.name(), resolvedType, merged);
			this.entities.put(id, updated);
			return updated;
		}
		GraphEntity entity = new GraphEntity(id, name.trim(), type, new LinkedHashMap<>(meta));
		this.entities.put(id, entity);
		return entity;
	}

	/**
	 * 加入关系；端点实体不存在时自动建立裸实体；跳过自环；重复关系（同端点同类型）合并元数据。
	 *
	 * @param sourceName 起点实体名
	 * @param targetName 终点实体名
	 * @param label      关系类型
	 * @param metadata   附加元数据（如来源文档 id），可空
	 * @return 新加入或合并后的关系；自环被跳过时返回 null
	 */
	public synchronized GraphRelation addRelation(String sourceName, String targetName,
			String label, Map<String, String> metadata) {
		Assert.notNull(sourceName, "sourceName 不能为 null");
		Assert.notNull(targetName, "targetName 不能为 null");
		Assert.notNull(label, "label 不能为 null");
		String sourceId = normalizeName(sourceName);
		String targetId = normalizeName(targetName);
		Assert.isTrue(!sourceId.isEmpty(), "起点实体名规范化后不能为空");
		Assert.isTrue(!targetId.isEmpty(), "终点实体名规范化后不能为空");
		if (sourceId.equals(targetId)) {
			// 自环对社区划分无贡献，直接跳过
			return null;
		}

		ensureEntity(sourceId, sourceName);
		ensureEntity(targetId, targetName);

		Map<String, String> meta = metadata == null ? Collections.emptyMap() : metadata;
		for (GraphRelation existing : this.relations) {
			if (existing.sourceId().equals(sourceId)
					&& existing.targetId().equals(targetId)
					&& existing.label().equals(label)) {
				Map<String, String> merged = new LinkedHashMap<>(existing.metadata());
				merged.putAll(meta);
				GraphRelation updated = new GraphRelation(sourceId, targetId, label, merged);
				this.relations.remove(existing);
				this.relations.add(updated);
				return updated;
			}
		}
		GraphRelation relation = new GraphRelation(sourceId, targetId, label,
				new LinkedHashMap<>(meta));
		this.relations.add(relation);
		return relation;
	}

	/**
	 * 确保规范化 id 对应的实体存在；不存在则以展示名创建裸实体。
	 */
	private void ensureEntity(String id, String displayName) {
		if (!this.entities.containsKey(id)) {
			this.entities.put(id, new GraphEntity(id, displayName.trim(), null,
					Collections.emptyMap()));
		}
	}

	/**
	 * 按规范化 id 取实体。
	 *
	 * @param id 规范化实体 id
	 * @return 实体，不存在返回 null
	 */
	public GraphEntity getEntity(String id) {
		return this.entities.get(id);
	}

	/**
	 * 所有实体的不可变视图。
	 *
	 * @return 实体集合快照
	 */
	public Collection<GraphEntity> entities() {
		return List.copyOf(this.entities.values());
	}

	/**
	 * 所有关系的不可变视图。
	 *
	 * @return 关系列表快照
	 */
	public List<GraphRelation> relations() {
		return List.copyOf(this.relations);
	}

	/**
	 * 实体数量。
	 *
	 * @return 实体数
	 */
	public int entityCount() {
		return this.entities.size();
	}

	/**
	 * 关系数量。
	 *
	 * @return 关系数
	 */
	public int relationCount() {
		return this.relations.size();
	}

	/**
	 * 取与指定实体相邻的实体（按无向图，忽略关系方向）。
	 *
	 * @param entityId 规范化实体 id
	 * @return 相邻实体列表快照，无相邻返回空列表
	 */
	public List<GraphEntity> neighbors(String entityId) {
		Assert.notNull(entityId, "entityId 不能为 null");
		List<GraphEntity> result = new ArrayList<>();
		for (GraphRelation relation : this.relations) {
			String other = null;
			if (relation.sourceId().equals(entityId)) {
				other = relation.targetId();
			} else if (relation.targetId().equals(entityId)) {
				other = relation.sourceId();
			}
			if (other != null) {
				GraphEntity neighbor = this.entities.get(other);
				if (neighbor != null) {
					result.add(neighbor);
				}
			}
		}
		return List.copyOf(result);
	}

	/**
	 * 取所有以指定实体为端点的关系（无论方向）。
	 *
	 * @param entityId 规范化实体 id
	 * @return 关联关系列表快照
	 */
	public List<GraphRelation> relationsOf(String entityId) {
		Assert.notNull(entityId, "entityId 不能为 null");
		List<GraphRelation> result = new ArrayList<>();
		for (GraphRelation relation : this.relations) {
			if (relation.sourceId().equals(entityId) || relation.targetId().equals(entityId)) {
				result.add(relation);
			}
		}
		return List.copyOf(result);
	}
}
