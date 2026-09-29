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

import com.sure.ai.model.ImagePart;

/**
 * 图片向量化器抽象：把图片片段转换为嵌入向量。
 *
 * <p>本模块零多模态 embedding 依赖，不内置任何图片向量模型。使用方可对接
 * CLIP / 多模态 embedding 服务实现本接口；未注入时图片仅做元数据登记，文本仍可检索。</p>
 *
 * @author sureai
 * @since 1.8.0
 */
@FunctionalInterface
public interface ImageEmbedder {

	/**
	 * 对图片片段向量化。
	 *
	 * @param imagePart 图片片段
	 * @return 图片嵌入向量
	 */
	float[] embed(ImagePart imagePart);
}
