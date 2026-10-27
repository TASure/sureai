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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.TextPart;

/**
 * 多模态文档：由若干 {@link MessagePart}（文本/图片/文档片段）组成的可检索文档。
 *
 * <p>文本片段（{@link TextPart}）会被聚合后向量化入库；图片片段（{@link ImagePart}）
 * 在未配置图片向量化能力时仅做元数据登记（记录存在与数量），检索命中后原样随文档返回。</p>
 *
 * @param id 文档唯一标识
 * @param parts 多模态片段列表
 * @param metadata 元数据
 * @author sureai
 * @since 1.8.0
 */
public record MultimodalDocument(String id, List<MessagePart> parts, Map<String, String> metadata) {

	/**
	 * 紧凑构造器：规范化元数据与片段列表为不可变副本。
	 *
	 * @param id 文档 id
	 * @param parts 片段列表
	 * @param metadata 元数据
	 */
	public MultimodalDocument {
		parts = parts == null ? List.of() : List.copyOf(parts);
		metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
	}

	/**
	 * 返回不可变的多模态片段视图。
	 *
	 * @return 片段列表（不可变）
	 */
	@Override
	public List<MessagePart> parts() {
		return Collections.unmodifiableList(parts);
	}

	/**
	 * 返回不可变的元数据视图。
	 *
	 * @return 元数据（不可变）
	 */
	@Override
	public Map<String, String> metadata() {
		return Collections.unmodifiableMap(metadata);
	}

	/**
	 * 创建多模态文档（无元数据）。
	 *
	 * @param id 文档 id
	 * @param parts 片段列表
	 * @return 多模态文档
	 */
	public static MultimodalDocument of(String id, List<MessagePart> parts) {
		return new MultimodalDocument(id, parts, Collections.emptyMap());
	}

	/**
	 * 创建多模态文档（带元数据）。
	 *
	 * @param id 文档 id
	 * @param parts 片段列表
	 * @param metadata 元数据
	 * @return 多模态文档
	 */
	public static MultimodalDocument of(String id, List<MessagePart> parts, Map<String, String> metadata) {
		return new MultimodalDocument(id, parts, metadata);
	}

	/**
	 * 聚合所有 {@link TextPart} 的文本（用换行连接），用于向量化与文本检索。
	 *
	 * @return 聚合文本，无文本片段时返回空串
	 */
	public String text() {
		StringBuilder sb = new StringBuilder();
		for (MessagePart part : this.parts) {
			if (part instanceof TextPart textPart) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(textPart.text());
			}
		}
		return sb.toString();
	}

	/**
	 * 返回其中的图片片段列表。
	 *
	 * @return 图片片段（防御性副本）
	 */
	public List<ImagePart> images() {
		List<ImagePart> images = new ArrayList<>();
		for (MessagePart part : this.parts) {
			if (part instanceof ImagePart imagePart) {
				images.add(imagePart);
			}
		}
		return images;
	}

	/**
	 * 图片片段数量。
	 *
	 * @return 图片数量
	 */
	public int imageCount() {
		return images().size();
	}
}
