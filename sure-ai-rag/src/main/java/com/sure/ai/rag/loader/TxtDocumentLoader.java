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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.rag.model.Document;
import com.sure.tool.lang.Assert;

/**
 * 纯文本加载器：把本地文件、输入流或内存字符串读取为单条 {@link Document}。
 *
 * <p>读取结果作为单条文档返回（id = 来源名/路径），元数据包含：</p>
 * <ul>
 *   <li>{@code source}：来源路径或名称；</li>
 *   <li>{@code loaded_at}：加载时刻的 ISO-8601 时间戳。</li>
 * </ul>
 *
 * <p>分块由调用方用 {@code TextSplitter} 完成。空内容返回空列表
 * （与 {@code TextSplitter} 对空文本的约定一致）。文件不存在或读取失败抛出
 * {@link IllegalStateException}（包装底层 {@link IOException}）。纯 JDK 实现，零依赖。</p>
 *
 * @author sureai
 * @since 0.3.0
 */
public class TxtDocumentLoader implements DocumentLoader {

	/** 元数据键：来源 */
	public static final String META_SOURCE = "source";
	/** 元数据键：加载时间戳 */
	public static final String META_LOADED_AT = "loaded_at";

	private final byte[] content;
	private final Charset charset;
	private final String sourceName;

	/**
	 * 以 UTF-8 读取本地文件。
	 *
	 * @param file 文本文件路径，不允许为 null
	 */
	public TxtDocumentLoader(Path file) {
		this(file, StandardCharsets.UTF_8);
	}

	/**
	 * 以指定字符集读取本地文件。
	 *
	 * @param file 文本文件路径，不允许为 null
	 * @param charset 字符集，不允许为 null
	 */
	public TxtDocumentLoader(Path file, Charset charset) {
		Assert.notNull(file, "file 不能为 null");
		Assert.notNull(charset, "charset 不能为 null");
		try {
			if (!Files.exists(file)) {
				throw new IllegalStateException("文件不存在: " + file);
			}
			this.content = Files.readAllBytes(file);
		} catch (IOException e) {
			throw new IllegalStateException("读取文件失败: " + file, e);
		}
		this.charset = charset;
		this.sourceName = file.toString();
	}

	/**
	 * 从输入流读取全部文本。
	 *
	 * @param inputStream 输入流，不允许为 null；读取后不关闭（由调用方管理）
	 * @param sourceName 来源名称（作为文档 id 与 source 元数据），不允许为空白
	 */
	public TxtDocumentLoader(InputStream inputStream, String sourceName) {
		Assert.notNull(inputStream, "inputStream 不能为 null");
		Assert.notBlank(sourceName, "sourceName 不能为空");
		try {
			this.content = inputStream.readAllBytes();
		} catch (IOException e) {
			throw new IllegalStateException("读取输入流失败: " + sourceName, e);
		}
		this.charset = StandardCharsets.UTF_8;
		this.sourceName = sourceName;
	}

	/**
	 * 直接包装内存中的文本内容。
	 *
	 * @param content 文本内容，可为 null（按空串处理）
	 * @param sourceName 来源名称，不允许为空白
	 */
	public TxtDocumentLoader(String content, String sourceName) {
		Assert.notBlank(sourceName, "sourceName 不能为空");
		this.content = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
		this.charset = StandardCharsets.UTF_8;
		this.sourceName = sourceName;
	}

	@Override
	public List<Document> load() {
		String text = new String(content, charset).trim();
		if (text.isEmpty()) {
			return new ArrayList<>(0);
		}
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(META_SOURCE, sourceName);
		metadata.put(META_LOADED_AT, Instant.now().toString());
		List<Document> documents = new ArrayList<>(1);
		documents.add(Document.of(sourceName, text, metadata));
		return documents;
	}

	/**
	 * 返回来源名称（文档 id 前缀）。
	 *
	 * @return 来源名称
	 */
	public String sourceName() {
		return sourceName;
	}
}
