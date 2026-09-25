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

package com.sure.ai.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sure.ai.exception.AiException;

/**
 * multipart/form-data 请求体拼装器（包内可见协作组件）。
 *
 * <p>从 {@link AbstractAiClient} 拆出：纯函数式拼装，不持有任何状态。boundary 由调用方
 * （{@code doPostMultipart}）生成并透传，拼装出的字节与重构前逐字节一致。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
final class MultipartBodyBuilder {

	private MultipartBodyBuilder() {
	}

	/**
	 * 构造 multipart/form-data 请求体字节。
	 *
	 * @param boundary        分隔符（不含前导 {@code --}）
	 * @param textFields      文本字段（name → value）
	 * @param fileField       文件字段名（{@code fileData} 为 null 时忽略）
	 * @param fileName        文件名
	 * @param fileContentType 文件 MIME 类型（null 时回退 application/octet-stream）
	 * @param fileData        文件二进制数据（可为 null，表示无文件段）
	 * @return 编码后的请求体字节
	 */
	static byte[] build(String boundary, Map<String, String> textFields, String fileField,
			String fileName, String fileContentType, byte[] fileData) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		String dashBoundary = "--" + boundary;
		String crlf = "\r\n";
		try {
			for (Map.Entry<String, String> e : textFields.entrySet()) {
				out.write((dashBoundary + crlf).getBytes(StandardCharsets.UTF_8));
				out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"" + crlf)
					.getBytes(StandardCharsets.UTF_8));
				out.write(crlf.getBytes(StandardCharsets.UTF_8));
				out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				out.write(crlf.getBytes(StandardCharsets.UTF_8));
			}
			if (fileData != null) {
				out.write((dashBoundary + crlf).getBytes(StandardCharsets.UTF_8));
				out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
					+ fileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
				out.write(("Content-Type: " + (fileContentType == null ? "application/octet-stream"
					: fileContentType) + crlf).getBytes(StandardCharsets.UTF_8));
				out.write(crlf.getBytes(StandardCharsets.UTF_8));
				out.write(fileData);
				out.write(crlf.getBytes(StandardCharsets.UTF_8));
			}
			out.write((dashBoundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiException("build multipart body failed: " + ex.getMessage(), ex);
		}
		return out.toByteArray();
	}
}
