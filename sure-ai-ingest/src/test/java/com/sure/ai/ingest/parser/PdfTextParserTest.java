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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import org.junit.Test;

import com.sure.ai.rag.model.Document;

/**
 * {@link PdfTextParser} 测试：手工构造最小 PDF 字节，零真实文件/网络。
 */
public class PdfTextParserTest {

	private final PdfTextParser parser = new PdfTextParser();

	@Test
	public void testExtractTjText() throws Exception {
		byte[] pdf = buildPdf("BT /F1 12 Tf 72 720 Td (Hello World) Tj ET");
		List<Document> docs = parser.parse(pdf, "doc.pdf", baseMeta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("Hello World"));
		assertEquals("pdf", docs.get(0).metadata().get("format"));
	}

	@Test
	public void testExtractTjArray() throws Exception {
		byte[] pdf = buildPdf("BT 72 720 Td [(Hello) 250 (RAG)] TJ ET");
		List<Document> docs = parser.parse(pdf, "doc.pdf", baseMeta());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("Hello"));
		assertTrue(text, text.contains("RAG"));
	}

	@Test
	public void testEscapeSequences() throws Exception {
		// (a\(b\)c) —— 转义括号应还原
		byte[] pdf = buildPdf("BT 72 720 Td (a\\(b\\)c) Tj ET");
		List<Document> docs = parser.parse(pdf, "doc.pdf", baseMeta());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("a(b)c"));
	}

	@Test
	public void testNoTextStreamReturnsEmpty() throws Exception {
		// 仅 BT/ET，无文本操作符
		byte[] pdf = buildPdf("BT ET");
		assertTrue(parser.parse(pdf, "doc.pdf", baseMeta()).isEmpty());
	}

	@Test
	public void testCorruptedBytesReturnsEmpty() {
		// 非 PDF 字节，宽容返回空而非抛异常
		byte[] garbage = "this is not a pdf at all".getBytes(StandardCharsets.UTF_8);
		assertTrue(parser.parse(garbage, "x.pdf", baseMeta()).isEmpty());
	}

	@Test
	public void testExtensions() {
		assertTrue(parser.extensions().contains(".pdf"));
	}

	/**
	 * 构造带 FlateDecode 内容流的最小 PDF。
	 *
	 * @param content 内容流原文
	 * @return PDF 字节
	 */
	private static byte[] buildPdf(String content) throws Exception {
		byte[] body = deflate(content.getBytes(StandardCharsets.ISO_8859_1));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
		out.write("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n".getBytes(StandardCharsets.ISO_8859_1));
		out.write("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n".getBytes(StandardCharsets.ISO_8859_1));
		out.write("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >> endobj\n"
				.getBytes(StandardCharsets.ISO_8859_1));
		out.write(("4 0 obj << /Length " + body.length + " /Filter /FlateDecode >>\nstream\n")
				.getBytes(StandardCharsets.ISO_8859_1));
		out.write(body);
		out.write("\nendstream\nendobj\ntrailer << /Size 5 /Root 1 0 R >>\nstartxref\n0\n%%EOF\n"
				.getBytes(StandardCharsets.ISO_8859_1));
		return out.toByteArray();
	}

	/**
	 * zlib 压缩内容流。
	 *
	 * @param in 原文
	 * @return 压缩字节
	 */
	private static byte[] deflate(byte[] in) {
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
		deflater.setInput(in);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		while (!deflater.finished()) {
			int read = deflater.deflate(buffer);
			out.write(buffer, 0, read);
		}
		deflater.end();
		return out.toByteArray();
	}

	/**
	 * 构造基础元数据。
	 *
	 * @return 元数据
	 */
	private static Map<String, String> baseMeta() {
		return new HashMap<>();
	}
}
