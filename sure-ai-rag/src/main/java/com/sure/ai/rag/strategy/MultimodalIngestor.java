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
package com.sure.ai.rag.strategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sure.ai.model.ImagePart;
import com.sure.ai.rag.embedding.EmbeddingProvider;
import com.sure.ai.rag.model.Vector;
import com.sure.ai.rag.store.VectorStore;
import com.sure.tool.lang.Assert;

/**
 * 多模态入库器：把 {@link MultimodalDocument} 的文本部分向量化入库，图片部分按需向量化
 * 或仅登记元数据。
 *
 * <p>行为：</p>
 * <ul>
 *   <li>文本部分：聚合所有 {@code TextPart} 文本，用 {@link EmbeddingProvider} 向量化后写入
 *       {@link VectorStore}；metadata 标注来源 id、图片数量等；</li>
 *   <li>图片部分：若注入了 {@link ImageEmbedder}，逐张图片向量化并写入向量库
 *       （metadata 标注 {@code kind=image} 与 {@code parentDocId}）；否则仅在文本向量的
 *       metadata 中登记图片数量，文本检索仍可工作；</li>
 *   <li>同时在内存注册表中保留 {@link MultimodalDocument} 原文，供检索命中后还原
 *       完整 parts（含图片引用）。</li>
 * </ul>
 *
 * @author sureai
 * @since 1.8.0
 */
public final class MultimodalIngestor {

	/** 标注向量类型（text/image）的 metadata 键。 */
	public static final String META_KIND = "mm_kind";
	/** 指向所属多模态文档 id 的 metadata 键（图片向量）。 */
	public static final String META_PARENT_DOC_ID = "mm_parent_doc_id";
	/** 标注图片数量的 metadata 键（文本向量）。 */
	public static final String META_IMAGE_COUNT = "mm_image_count";

	private final VectorStore store;
	private final EmbeddingProvider textEmbeddingProvider;
	private final ImageEmbedder imageEmbedder;
	private final Map<String, MultimodalDocument> registry = new ConcurrentHashMap<>();

	private MultimodalIngestor(Builder builder) {
		this.store = builder.store;
		this.textEmbeddingProvider = builder.textEmbeddingProvider;
		this.imageEmbedder = builder.imageEmbedder;
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 入库一个多模态文档。
	 *
	 * @param document 多模态文档
	 */
	public void ingest(MultimodalDocument document) {
		Assert.notNull(document, "document 不能为 null");
		Assert.notNull(document.id(), "document.id 不能为 null");

		this.registry.put(document.id(), document);

		// 1. 文本部分向量化入库
		String text = document.text();
		if (text != null && !text.isEmpty()) {
			float[] textEmbedding = this.textEmbeddingProvider.embed(text);
			Map<String, String> meta = new HashMap<>(document.metadata());
			meta.put(META_KIND, "text");
			meta.put(META_IMAGE_COUNT, String.valueOf(document.imageCount()));
			this.store.add(Vector.of(document.id(), textEmbedding, text, meta));
		}

		// 2. 图片部分：注入了 ImageEmbedder 则逐张向量化入库；否则仅登记元数据
		if (this.imageEmbedder != null) {
			List<ImagePart> images = document.images();
			for (int i = 0; i < images.size(); i++) {
				float[] imageEmbedding = this.imageEmbedder.embed(images.get(i));
				Map<String, String> meta = new HashMap<>(document.metadata());
				meta.put(META_KIND, "image");
				meta.put(META_PARENT_DOC_ID, document.id());
				this.store.add(Vector.of(document.id() + "#img" + i, imageEmbedding, "[image]", meta));
			}
		}
	}

	/**
	 * 批量入库。
	 *
	 * @param documents 多模态文档列表
	 */
	public void ingestAll(List<MultimodalDocument> documents) {
		Assert.notNull(documents, "documents 不能为 null");
		for (MultimodalDocument document : documents) {
			ingest(document);
		}
	}

	/**
	 * 按 id 取回注册的多模态文档。
	 *
	 * @param id 文档 id
	 * @return 多模态文档，不存在返回 null
	 */
	public MultimodalDocument get(String id) {
		return this.registry.get(id);
	}

	/**
	 * 返回当前注册的文档数量。
	 *
	 * @return 数量
	 */
	public int size() {
		return this.registry.size();
	}

	/**
	 * MultimodalIngestor 构造器。
	 */
	public static final class Builder {

		private VectorStore store;
		private EmbeddingProvider textEmbeddingProvider;
		private ImageEmbedder imageEmbedder;

		private Builder() {
		}

		/**
		 * 设置向量存储（必填）。
		 *
		 * @param store 向量存储
		 * @return this
		 */
		public Builder store(VectorStore store) {
			this.store = store;
			return this;
		}

		/**
		 * 设置文本向量化实现（必填）。
		 *
		 * @param textEmbeddingProvider 文本向量化实现
		 * @return this
		 */
		public Builder textEmbeddingProvider(EmbeddingProvider textEmbeddingProvider) {
			this.textEmbeddingProvider = textEmbeddingProvider;
			return this;
		}

		/**
		 * 注入图片向量化器（可选；不注入则图片仅登记元数据）。
		 *
		 * @param imageEmbedder 图片向量化器
		 * @return this
		 */
		public Builder imageEmbedder(ImageEmbedder imageEmbedder) {
			this.imageEmbedder = imageEmbedder;
			return this;
		}

		/**
		 * 构建入库器。
		 *
		 * @return 入库器
		 */
		public MultimodalIngestor build() {
			Assert.notNull(this.store, "store 不能为 null");
			Assert.notNull(this.textEmbeddingProvider, "textEmbeddingProvider 不能为 null");
			return new MultimodalIngestor(this);
		}
	}
}
