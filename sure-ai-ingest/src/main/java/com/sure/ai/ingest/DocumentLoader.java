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

package com.sure.ai.ingest;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import com.sure.ai.rag.model.Document;

/**
 * 文档加载器 SPI：把「来源」（本地文件 / 网络 URL）读取为带元数据的 {@link Document} 列表。
 *
 * <p>与 {@code sure-ai-rag} 既有的无参 {@code load()} 加载器不同，本 SPI 以「来源」为入参，
 * 并按扩展名 / Content-Type 路由到对应格式的 {@link com.sure.ai.ingest.spi.DocumentParser}，
 * 便于一站式处理目录混合格式文件。</p>
 *
 * <p>职责边界：加载器只负责「来源 → 纯文本 + 元数据」，<b>不负责分块与向量化</b>。
 * 分块由 rag 的 {@code TextSplitter} 完成，入库由 {@code RagPipeline} 负责。
 * 加载失败（文件不存在、读取错误、HTTP 非 2xx、不支持的格式）抛出 {@link IllegalStateException}；
 * 空来源 / 无文本层返回空列表。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public interface DocumentLoader {

	/** 元数据键：来源标识（路径或 URL）。 */
	String META_SOURCE = "source";
	/** 元数据键：加载时刻的 ISO-8601 时间戳。 */
	String META_LOADED_AT = "loaded_at";
	/** 元数据键：格式（txt/md/html/pdf/docx/xlsx/pptx 等）。 */
	String META_FORMAT = "format";
	/** 元数据键：HTTP 响应 Content-Type（仅 URL 来源）。 */
	String META_CONTENT_TYPE = "content_type";

	/**
	 * 加载本地路径，返回带元数据的文档列表。
	 *
	 * <p>实现须保证：返回列表不含 {@code null}；空文件 / 无文本层返回空列表；
	 * 每条文档至少含 {@link #META_SOURCE} 元数据。</p>
	 *
	 * @param path 本地文件路径，不允许为 null
	 * @return 文档列表，不会返回 {@code null}
	 * @throws IllegalStateException 文件不存在、读取失败或格式不受支持时抛出
	 */
	List<Document> load(Path path);

	/**
	 * 加载网络 / 任意 URI，返回带元数据的文档列表。
	 *
	 * <p>{@code file:} scheme 由实现决定是否支持（{@code FileSystemLoader} 支持并转路径读取）；
	 * http(s) 由 {@code URLLoader} 下载后路由。</p>
	 *
	 * @param uri 来源 URI，不允许为 null
	 * @return 文档列表，不会返回 {@code null}
	 * @throws IllegalStateException 下载失败、HTTP 非 2xx 或格式不受支持时抛出
	 */
	List<Document> load(URI uri);
}
