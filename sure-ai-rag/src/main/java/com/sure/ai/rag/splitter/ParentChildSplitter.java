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
package com.sure.ai.rag.splitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.rag.model.Document;
import com.sure.tool.lang.Assert;

/**
 * 父子分块器（Parent-Child / Small-to-Big）：先切成较大的父块，再把每个父块
 * 切成更小的子块。
 *
 * <p>用途：检索时用语义更集中、向量更准的<b>子块</b>做召回，命中后返回信息量更完整的
 * <b>父块全文</b>——兼顾「小而准的召回」与「大而全的上下文」。</p>
 *
 * <p>产出：</p>
 * <ul>
 *   <li>父块：id 形如 {@code sourceId#p0}，正文为父块全文；</li>
 *   <li>子块：id 形如 {@code sourceId#p0#c0}，正文为子块文本，metadata 携带
 *       {@code parentId = 父块 id}，便于命中后聚合回父块。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class ParentChildSplitter {

	/** 子块 metadata 中记录父块 id 的键。 */
	public static final String META_PARENT_ID = "parentId";

	private final TextSplitter parentSplitter;
	private final TextSplitter childSplitter;

	/**
	 * 全参构造器。
	 *
	 * @param parentSplitter 父块切分器（块较大，如 RecursiveCharacter）
	 * @param childSplitter 子块切分器（块较小，如 FixedSize）
	 */
	public ParentChildSplitter(TextSplitter parentSplitter, TextSplitter childSplitter) {
		this.parentSplitter = Assert.notNull(parentSplitter, "parentSplitter 不能为 null");
		this.childSplitter = Assert.notNull(childSplitter, "childSplitter 不能为 null");
	}

	/**
	 * 执行父子两层切分。
	 *
	 * @param sourceId 来源标识
	 * @param text 原始文本
	 * @return 父子分块结果
	 */
	public ParentChunks split(String sourceId, String text) {
		Assert.notNull(sourceId, "sourceId 不能为 null");
		if (text == null || text.isEmpty()) {
			return new ParentChunks(new ArrayList<>(0), new ArrayList<>(0));
		}
		List<String> parents = this.parentSplitter.split(text);
		List<Document> parentDocs = new ArrayList<>(parents.size());
		List<Document> childDocs = new ArrayList<>();
		for (int p = 0; p < parents.size(); p++) {
			String parentId = sourceId + "#p" + p;
			String parentText = parents.get(p);
			parentDocs.add(Document.of(parentId, parentText));
			List<String> children = this.childSplitter.split(parentText);
			for (int c = 0; c < children.size(); c++) {
				Map<String, String> meta = new HashMap<>(1);
				meta.put(META_PARENT_ID, parentId);
				childDocs.add(Document.of(parentId + "#c" + c, children.get(c), meta));
			}
		}
		return new ParentChunks(parentDocs, childDocs);
	}

	/**
	 * 父子分块结果。
	 *
	 * @param parents 父块文档列表
	 * @param children 子块文档列表（metadata 含 parentId）
	 */
	public record ParentChunks(List<Document> parents, List<Document> children) {

		/** 紧凑构造器：防御性拷贝。 */
		public ParentChunks {
			parents = parents == null ? new ArrayList<>(0) : List.copyOf(parents);
			children = children == null ? new ArrayList<>(0) : List.copyOf(children);
		}

		/**
		 * 返回不可变的父块视图。
		 *
		 * @return 父块列表（不可变）
		 */
		public List<Document> parents() {
			return Collections.unmodifiableList(parents);
		}

		/**
		 * 返回不可变的子块视图。
		 *
		 * @return 子块列表（不可变）
		 */
		public List<Document> children() {
			return Collections.unmodifiableList(children);
		}

		/**
		 * 以父块 id 为键建立索引，便于检索后按 parentId 取回父块全文。
		 *
		 * @return 不可变的父块索引（parentId → 父块文档）
		 */
		public Map<String, Document> parentIndex() {
			Map<String, Document> map = new HashMap<>();
			for (Document parent : parents) {
				map.put(parent.id(), parent);
			}
			return Collections.unmodifiableMap(map);
		}
	}
}
