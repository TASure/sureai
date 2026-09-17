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

package com.sure.ai.model;

/**
 * 文档消息片段（如 PDF 文档输入）。
 *
 * <p>支持两种形式：</p>
 * <ul>
 *   <li><b>base64 内联</b>：通过 {@link #ofBase64(String, String, String)} 传入文件名、
 *       MIME 类型与 base64 数据；</li>
 *   <li><b>文件引用</b>：通过 {@link #ofFileId(String)} 传入 OpenAI 文件 ID（文档已上传至
 *       文件服务后引用）。</li>
 * </ul>
 *
 * @param type     片段类型，固定为 "document"
 * @param name     文件名（base64 形式时提供，文件引用形式时可为 null）
 * @param mimeType MIME 类型（如 application/pdf），文件引用形式时可为 null
 * @param data     base64 编码的文档数据，文件引用形式时为 null
 * @param fileId   OpenAI 文件 ID，base64 内联形式时为 null
 * @author sureai
 * @since 0.2.0
 */
public record DocumentPart(String type, String name, String mimeType,
		String data, String fileId) implements MessagePart {

	/**
	 * 紧凑构造器：规范化空白文件 ID。类型固定为 "document"，由工厂方法传入。
	 *
	 * @param name     文件名
	 * @param mimeType MIME 类型
	 * @param data     base64 数据
	 * @param fileId   文件 ID
	 */
	public DocumentPart {
		if (fileId != null && fileId.isBlank()) {
			fileId = null;
		}
	}

	/**
	 * 按 base64 数据构造。
	 *
	 * @param name     文件名（如 contract.pdf）
	 * @param mimeType MIME 类型（如 application/pdf）
	 * @param base64Data base64 编码的文档数据
	 * @return 文档片段
	 */
	public static DocumentPart ofBase64(String name, String mimeType, String base64Data) {
		return new DocumentPart("document", name, mimeType, base64Data, null);
	}

	/**
	 * 按已上传文件 ID 构造。
	 *
	 * @param fileId OpenAI 文件 ID
	 * @return 文档片段
	 */
	public static DocumentPart ofFileId(String fileId) {
		return new DocumentPart("document", null, null, null, fileId);
	}

	@Override
	public String type() {
		return "document";
	}
}
