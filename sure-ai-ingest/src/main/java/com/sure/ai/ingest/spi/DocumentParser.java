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

package com.sure.ai.ingest.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sure.ai.rag.model.Document;

/**
 * 单格式文档解析器：把原始字节抽取为 {@link Document} 列表。
 *
 * <p>解析器是无状态、可并发复用的；加载器按扩展名路由到对应解析器。
 * 空内容 / 无文本层返回空列表；解析失败（损坏、不支持的子集）应尽量宽容返回空，
 * 仅在确属致命错误时抛出 {@link IllegalStateException}。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public interface DocumentParser {

	/**
	 * 解析字节为文档列表。
	 *
	 * @param content 原始字节，不允许为 null
	 * @param source 来源标识（作为文档 id 与 source 元数据），不允许为空白
	 * @param metadata 基础元数据（已含 source、loaded_at，URL 来源另含 content_type），
	 *                 解析器可追加格式专属键（如 {@code format}），不允许为 null
	 * @return 文档列表，不会返回 {@code null}；空内容返回空列表
	 */
	List<Document> parse(byte[] content, String source, Map<String, String> metadata);

	/**
	 * 本解析器支持的扩展名集合（小写、含前导点，如 {@code .txt}）。
	 *
	 * @return 扩展名集合，不会返回 {@code null}
	 */
	Set<String> extensions();
}
