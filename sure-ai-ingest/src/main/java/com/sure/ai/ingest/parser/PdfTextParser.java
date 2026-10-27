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

package com.sure.ai.ingest.parser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import com.sure.ai.ingest.DocumentLoader;
import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * PDF 文本层有限提取器：纯 JDK 手写，不引入 PDFBox。
 *
 * <p>提取流程：</p>
 * <ol>
 *   <li>在文件字节中扫描所有 {@code stream ... endstream} 块；</li>
 *   <li>对每个块尝试 {@link Inflater}（zlib / 裸 deflate 两种）解压 FlateDecode 流，
 *       解压失败则按原始字节处理；</li>
 *   <li>在解压后的内容流中扫描文本显示操作符 {@code Tj} / {@code TJ} 对应的字面串
 *       {@code ( ... )} 与十六进制串 {@code < ... >}，还原转义与八进制转义后拼接文本。</li>
 * </ol>
 *
 * <p><b>支持子集</b>：仅提取内容流中以标准单字节字体写入、顺序排布的文本操作符
 * {@code (text) Tj} 与 {@code [ ... ] TJ}。</p>
 *
 * <p><b>明确限制（不处理）</b>：</p>
 * <ul>
 *   <li>不做字体编码 / ToUnicode CMap / CID 字体映射（非 ASCII 文字可能乱码或缺失）；</li>
 *   <li>不处理扫描件 / 纯图片 PDF（无文本层，返回空）；</li>
 *   <li>不处理加密 PDF；</li>
 *   <li>不还原多栏、旋转、绝对坐标的阅读顺序；</li>
 *   <li>不解析 xref/trailer 做对象导航，采用全流扫描的宽容策略。</li>
 * </ul>
 *
 * <p>损坏或不支持的 PDF 不抛异常，按「无文本层」返回空列表，避免中断入库管线。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public final class PdfTextParser implements DocumentParser {

	/** 支持的扩展名。 */
	private static final Set<String> EXTENSIONS = Set.of(".pdf");

	private static final String STREAM = "stream";
	private static final String END_STREAM = "endstream";

	@Override
	public Set<String> extensions() {
		return EXTENSIONS;
	}

	@Override
	public List<Document> parse(byte[] content, String source, Map<String, String> metadata) {
		String text = extractText(content).trim().replaceAll("[ \\t]+", " ");
		if (text.isEmpty()) {
			return new ArrayList<>(0);
		}
		metadata.put(DocumentLoader.META_FORMAT, "pdf");
		List<Document> documents = new ArrayList<>(1);
		documents.add(Document.of(source, text, metadata));
		return documents;
	}

	/**
	 * 扫描全部流并拼接提取到的文本。
	 *
	 * @param data PDF 字节
	 * @return 提取到的纯文本（可能为空串）
	 */
	private static String extractText(byte[] data) {
		StringBuilder out = new StringBuilder();
		for (byte[] body : streamBodies(data)) {
			byte[] inflated = inflate(body);
			byte[] candidate = (inflated != null ? inflated : body);
			String decoded = new String(candidate, StandardCharsets.ISO_8859_1);
			out.append(collectText(decoded)).append(' ');
		}
		return out.toString();
	}

	/**
	 * 扫描 {@code stream ... endstream}，取出每个流的字节体。
	 *
	 * @param data PDF 字节
	 * @return 流体列表
	 */
	private static List<byte[]> streamBodies(byte[] data) {
		List<byte[]> bodies = new ArrayList<>();
		int n = data.length;
		int idx = 0;
		while (idx < n) {
			int start = indexOfAscii(data, STREAM, idx);
			if (start < 0) {
				break;
			}
			int bodyStart = start + STREAM.length();
			// 跳过流数据前的 EOL（\r\n 或 \n）
			if (bodyStart < n && data[bodyStart] == '\r') {
				bodyStart++;
			}
			if (bodyStart < n && data[bodyStart] == '\n') {
				bodyStart++;
			}
			int end = indexOfAscii(data, END_STREAM, bodyStart);
			if (end < 0) {
				break;
			}
			// 去掉 endstream 前的尾随 EOL
			int bodyEnd = end;
			while (bodyEnd > bodyStart
					&& (data[bodyEnd - 1] == '\r' || data[bodyEnd - 1] == '\n')) {
				bodyEnd--;
			}
			if (bodyEnd > bodyStart) {
				byte[] body = new byte[bodyEnd - bodyStart];
				System.arraycopy(data, bodyStart, body, 0, bodyEnd - bodyStart);
				bodies.add(body);
			}
			idx = end + END_STREAM.length();
		}
		return bodies;
	}

	/**
	 * 在字节数组中查找 ASCII 子串。
	 *
	 * @param data 数据
	 * @param token ASCII 子串
	 * @param from 起始下标
	 * @return 下标，未找到返回 -1
	 */
	private static int indexOfAscii(byte[] data, String token, int from) {
		int n = data.length;
		int len = token.length();
		outer:
		for (int i = from; i + len <= n; i++) {
			for (int j = 0; j < len; j++) {
				if (data[i + j] != (byte) token.charAt(j)) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	/**
	 * 尝试按 zlib（RFC1950）与裸 deflate 解压；均失败返回 null。
	 *
	 * @param in 压缩字节
	 * @return 解压字节，或 null
	 */
	private static byte[] inflate(byte[] in) {
		byte[] result = doInflate(in, false);
		if (result == null || result.length == 0) {
			byte[] raw = doInflate(in, true);
			if (raw != null && raw.length > 0) {
				return raw;
			}
		}
		return result;
	}

	/**
	 * 单次解压尝试。
	 *
	 * @param in 压缩字节
	 * @param nowrap true=裸 deflate，false=zlib
	 * @return 解压字节，失败返回 null
	 */
	private static byte[] doInflate(byte[] in, boolean nowrap) {
		Inflater inflater = new Inflater(nowrap);
		try {
			inflater.setInput(in);
			byte[] buffer = new byte[4096];
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			while (!inflater.finished()) {
				int read = inflater.inflate(buffer);
				if (read == 0) {
					break;
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		} catch (DataFormatException e) {
			return null;
		} finally {
			inflater.end();
		}
	}

	/**
	 * 在内容流中收集所有文本字面串与十六进制串。
	 *
	 * @param content 内容流（ISO-8859-1 解码）
	 * @return 拼出的文本
	 */
	private static String collectText(String content) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		int n = content.length();
		while (i < n) {
			char c = content.charAt(i);
			if (c == '(') {
				i = readLiteralString(content, i + 1, out);
			} else if (c == '<' && i + 1 < n && content.charAt(i + 1) != '<') {
				i = readHexString(content, i, out);
			} else {
				i++;
			}
		}
		return out.toString();
	}

	/**
	 * 读取一个圆括号字面串（处理转义与嵌套括号），追加到 out。
	 *
	 * @param content 内容流
	 * @param i 左括号之后的下标
	 * @param out 输出
	 * @return 右括号之后的下标
	 */
	private static int readLiteralString(String content, int i, StringBuilder out) {
		int n = content.length();
		int depth = 1;
		while (i < n && depth > 0) {
			char d = content.charAt(i);
			if (d == '\\') {
				i++;
				if (i >= n) {
					break;
				}
				char e = content.charAt(i);
				i = appendEscaped(e, i, content, out);
			} else {
				if (d == '(') {
					depth++;
				} else if (d == ')') {
					depth--;
					if (depth == 0) {
						i++;
						break;
					}
				}
				if (depth > 0) {
					out.append(d);
				}
				i++;
			}
		}
		out.append(' ');
		return i;
	}

	/**
	 * 处理转义字符。
	 *
	 * @param e 转义后字符
	 * @param i 当前下标
	 * @param content 内容流
	 * @param out 输出
	 * @return 处理后下标
	 */
	private static int appendEscaped(char e, int i, String content, StringBuilder out) {
		switch (e) {
			case 'n':
				out.append('\n');
				break;
			case 'r':
				out.append(' ');
				break;
			case 't':
				out.append(' ');
				break;
			case 'b':
				out.append('\b');
				break;
			case 'f':
				out.append('\f');
				break;
			case '(':
				out.append('(');
				break;
			case ')':
				out.append(')');
				break;
			case '\\':
				out.append('\\');
				break;
			default:
				if (e >= '0' && e <= '7') {
					StringBuilder oct = new StringBuilder();
					oct.append(e);
					int j = i;
					for (int k = 0; k < 2 && j + 1 < content.length()
							&& content.charAt(j + 1) >= '0' && content.charAt(j + 1) <= '7'; k++) {
						j++;
						oct.append(content.charAt(j));
					}
					try {
						out.append((char) Integer.parseInt(oct.toString(), 8));
					} catch (NumberFormatException ignore) {
						// 忽略非法八进制
					}
					return j + 1;
				}
				out.append(e);
		}
		return i + 1;
	}

	/**
	 * 读取一个尖括号十六进制串。
	 *
	 * @param content 内容流
	 * @param i 左尖括号下标
	 * @param out 输出
	 * @return 右尖括号之后的下标
	 */
	private static int readHexString(String content, int i, StringBuilder out) {
		int close = content.indexOf('>', i + 1);
		if (close < 0) {
			return i + 1;
		}
		String hex = content.substring(i + 1, close).replaceAll("[^0-9A-Fa-f]", "");
		if (hex.length() % 2 != 0) {
			hex += "0";
		}
		for (int k = 0; k + 1 < hex.length(); k += 2) {
			try {
				out.append((char) Integer.parseInt(hex.substring(k, k + 2), 16));
			} catch (NumberFormatException ignore) {
				// 忽略非法十六进制
			}
		}
		return close + 1;
	}
}
