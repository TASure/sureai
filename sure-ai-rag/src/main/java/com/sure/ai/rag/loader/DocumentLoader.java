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

package com.sure.ai.rag.loader;

import java.util.List;

import com.sure.ai.rag.model.Document;

/**
 * 文档加载器：把「来源」（本地文件、URL、字符串等）读取为带元数据的 {@link Document} 列表。
 *
 * <p>职责边界：加载器只负责「来源 → 纯文本 + 元数据」，<b>不负责分块与向量化</b>。
 * 分块由 {@code TextSplitter} 完成，向量化与入库由 {@code RagPipeline} 负责。
 * 简单加载器（如 {@link TxtDocumentLoader}、{@link UrlDocumentLoader}）通常返回单条文档，
 * 调用方再用分块器切成适合向量化的小块；复杂加载器也可在内部直接产出多个逻辑分块。</p>
 *
 * <p>加载失败（文件不存在、读取错误、HTTP 非 2xx 等）应抛出 {@link IllegalStateException}，
 * 不返回 {@code null}。空来源（空文件/空响应）返回空列表，与 {@code TextSplitter}
 * 对空文本的约定保持一致。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public interface DocumentLoader {

	/**
	 * 加载来源，返回带元数据的文档列表。
	 *
	 * <p>实现须保证：返回列表不含 {@code null}；空来源返回空列表；
	 * 每条文档至少含 {@code source} 元数据（来源标识）。</p>
	 *
	 * @return 文档列表，不会返回 {@code null}
	 * @throws IllegalStateException 读取/下载失败时抛出（包装底层 IO 异常）
	 */
	List<Document> load();
}
