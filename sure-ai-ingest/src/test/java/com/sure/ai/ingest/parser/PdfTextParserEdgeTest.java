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
 * {@link PdfTextParser} 边界测试：手工构造内容流覆盖转义字符、八进制、十六进制串、
 * 裸 deflate 回退、损坏流宽容处理与 EOL 变体。零真实文件/网络。
 */
public class PdfTextParserEdgeTest {

	private final PdfTextParser parser = new PdfTextParser();

	/**
	 * CRLF 行尾：stream 后 \r\n、endstream 前 \r\n 均被正确剥离。
	 */
	@Test
	public void testCrlfEol() {
		byte[] pdf = buildRawPdf("BT 72 720 Td (CRLF) Tj ET", true);
		List<Document> docs = parser.parse(pdf, "c.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("CRLF"));
	}

	/**
	 * 无 endstream 的流：扫描宽容中断，返回空列表。
	 */
	@Test
	public void testStreamWithoutEndstream() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeAscii(out, "%PDF-1.4\n");
		writeAscii(out, "stream\nBT (nope) Tj ET");
		assertTrue(parser.parse(out.toByteArray(), "x.pdf", meta()).isEmpty());
	}

	/**
	 * 裸 deflate（nowrap）内容流：zlib 解压失败后回退裸 inflate。
	 */
	@Test
	public void testRawDeflateFallback() {
		byte[] body = nowrapDeflate("BT (RAWDEFLATE) Tj ET".getBytes(StandardCharsets.ISO_8859_1));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeAscii(out, "%PDF-1.4\n");
		writeAscii(out, "1 0 obj << /Length " + body.length + " /Filter /FlateDecode >>\nstream\n");
		out.write(body, 0, body.length);
		writeAscii(out, "\nendstream\nendobj\n%%EOF\n");
		List<Document> docs = parser.parse(out.toByteArray(), "r.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("RAWDEFLATE"));
	}

	/**
	 * 非 deflate 的损坏流：Inflater 抛 DataFormatException，宽容按无文本返回空。
	 */
	@Test
	public void testCorruptStreamTolerant() {
		byte[] garbage = new byte[] { (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD,
				(byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44 };
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeAscii(out, "%PDF-1.4\n");
		writeAscii(out, "1 0 obj << /Length " + garbage.length + " >>\nstream\n");
		out.write(garbage, 0, garbage.length);
		writeAscii(out, "\nendstream\nendobj\n%%EOF\n");
		assertTrue(parser.parse(out.toByteArray(), "g.pdf", meta()).isEmpty());
	}

	/**
	 * 截断的 zlib 流：inflate 返回 0 进度而非抛错，宽容返回空。
	 */
	@Test
	public void testTruncatedStreamTolerant() {
		byte[] full = zlibDeflate("BT (TRUNC) Tj ET".getBytes(StandardCharsets.ISO_8859_1));
		byte[] truncated = new byte[Math.min(5, full.length)];
		System.arraycopy(full, 0, truncated, 0, truncated.length);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeAscii(out, "%PDF-1.4\n");
		writeAscii(out, "1 0 obj << /Length " + truncated.length + " >>\nstream\n");
		out.write(truncated, 0, truncated.length);
		writeAscii(out, "\nendstream\nendobj\n%%EOF\n");
		assertTrue(parser.parse(out.toByteArray(), "t.pdf", meta()).isEmpty());
	}

	/**
	 * 转义字符：\n \r \t \b \f \\ 与转义括号。
	 */
	@Test
	public void testEscapeChars() {
		// 内容流字面字节：BT (a\nb\rc\td\be\f\\(x\)) Tj ET
		String content = "BT (a\\nb\\rc\\td\\be\\f\\\\(x\\)) Tj ET";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "e.pdf", meta());
		assertEquals(1, docs.size());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("a"));
		assertTrue(text, text.contains("e"));
		assertTrue(text, text.contains("x"));
	}

	/**
	 * 八进制转义：\101 还原为 'A'。
	 */
	@Test
	public void testOctalEscape() {
		String content = "BT (x\\101y) Tj ET";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "o.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("xAy"));
	}

	/**
	 * 未知转义字符按原样保留。
	 */
	@Test
	public void testUnknownEscape() {
		String content = "BT (q\\z) Tj ET";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "u.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("qz"));
	}

	/**
	 * 字面串以反斜杠结尾：宽容中断。
	 */
	@Test
	public void testLiteralEndsWithBackslash() {
		// 流内容以反斜杠收尾：BT (abc\
		String content = "BT (abc\\";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "b.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("abc"));
	}

	/**
	 * 嵌套括号：(()) 深度计数。
	 */
	@Test
	public void testNestedParens() {
		String content = "BT ((nested)) Tj ET";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "n.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("nested"));
	}

	/**
	 * 十六进制串：<48656C6C6F> 还原为 Hello。
	 */
	@Test
	public void testHexString() {
		String content = "BT <48656C6C6F> Tj ET";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "h.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("Hello"));
	}

	/**
	 * 奇数长度十六进制串补 0 对齐。
	 */
	@Test
	public void testOddLengthHex() {
		String content = "BT <48656C6C6> Tj ET";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "o2.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("He"));
	}

	/**
	 * 十六进制串含非十六进制字符：剥离后解析。
	 */
	@Test
	public void testHexWithJunkChars() {
		String content = "BT <48ZZ65> Tj ET";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "j.pdf", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("He"));
	}

	/**
	 * 无右尖括号的十六进制串：宽容跳过。
	 */
	@Test
	public void testHexWithoutClose() {
		String content = "BT <48656C Tj ET";
		List<Document> docs = parser.parse(buildRawPdf(content, false), "w.pdf", meta());
		// 宽容处理：不抛异常
		assertTrue(docs != null);
	}

	/**
	 * 构造未压缩内容流的最小 PDF。
	 *
	 * @param content 内容流原文
	 * @param crlf    是否使用 CRLF 行尾
	 * @return PDF 字节
	 */
	private static byte[] buildRawPdf(String content, boolean crlf) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeAscii(out, "%PDF-1.4\n");
		writeAscii(out, "1 0 obj << /Length " + content.length() + " >>\n");
		if (crlf) {
			writeAscii(out, "stream\r\n");
		} else {
			writeAscii(out, "stream\n");
		}
		writeAscii(out, content);
		if (crlf) {
			writeAscii(out, "\r\nendstream\r\nendobj\n%%EOF\n");
		} else {
			writeAscii(out, "\nendstream\nendobj\n%%EOF\n");
		}
		return out.toByteArray();
	}

	/**
	 * zlib 压缩。
	 *
	 * @param in 原文
	 * @return 压缩字节
	 */
	private static byte[] zlibDeflate(byte[] in) {
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
		deflater.setInput(in);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[128];
		while (!deflater.finished()) {
			int read = deflater.deflate(buffer);
			out.write(buffer, 0, read);
		}
		deflater.end();
		return out.toByteArray();
	}

	/**
	 * 裸 deflate 压缩（nowrap）。
	 *
	 * @param in 原文
	 * @return 裸 deflate 字节
	 */
	private static byte[] nowrapDeflate(byte[] in) {
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
		deflater.setInput(in);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[128];
		while (!deflater.finished()) {
			int read = deflater.deflate(buffer);
			out.write(buffer, 0, read);
		}
		deflater.end();
		return out.toByteArray();
	}

	/**
	 * 以 ISO-8859-1 写 ASCII。
	 *
	 * @param out 输出
	 * @param s   字符串
	 */
	private static void writeAscii(ByteArrayOutputStream out, String s) {
		out.write(s.getBytes(StandardCharsets.ISO_8859_1), 0, s.length());
	}

	/**
	 * 基础元数据。
	 *
	 * @return 元数据
	 */
	private static Map<String, String> meta() {
		return new HashMap<>();
	}
}
