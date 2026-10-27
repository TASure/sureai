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

package com.sure.ai.ingest.poi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import com.sure.ai.rag.model.Document;

/**
 * POI 解析器边界测试：截断合法 OOXML 字节（损坏的 zip 流）触发 IOException 包装分支，
 * 空工作簿（无工作表）与空幻灯片（无文本）返回空列表。零真实文件/网络。
 */
public class PoiParsersEdgeTest {

	/**
	 * 基础元数据。
	 *
	 * @return 元数据
	 */
	private static Map<String, String> meta() {
		return new HashMap<>();
	}

	/**
	 * 截断字节数组：取前 fraction 比例，模拟损坏的 zip 流。
	 *
	 * @param data      原字节
	 * @param fraction  保留比例（0..1）
	 * @return 截断后的字节
	 */
	private static byte[] truncated(byte[] data, double fraction) {
		int len = (int) (data.length * fraction);
		byte[] out = new byte[len];
		System.arraycopy(data, 0, out, 0, len);
		return out;
	}

	/**
	 * 构造合法 DOCX 字节。
	 *
	 * @return docx 字节
	 * @throws Exception 写文档失败
	 */
	private static byte[] goodDocx() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XWPFDocument doc = new XWPFDocument()) {
			XWPFParagraph p = doc.createParagraph();
			p.createRun().setText("body");
			doc.write(out);
		}
		return out.toByteArray();
	}

	/**
	 * 构造合法 XLSX 字节。
	 *
	 * @return xlsx 字节
	 * @throws Exception 写表格失败
	 */
	private static byte[] goodXlsx() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			XSSFSheet sheet = wb.createSheet("S1");
			Row row = sheet.createRow(0);
			row.createCell(0).setCellValue("v");
			wb.write(out);
		}
		return out.toByteArray();
	}

	/**
	 * 构造合法 PPTX 字节。
	 *
	 * @return pptx 字节
	 * @throws Exception 写幻灯片失败
	 */
	private static byte[] goodPptx() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XMLSlideShow show = new XMLSlideShow()) {
			XSLFSlide slide = show.createSlide();
			slide.createTextBox().setText("t");
			show.write(out);
		}
		return out.toByteArray();
	}

	/**
	 * DOCX：截断的 OOXML 字节触发 IOException，包装为 IllegalStateException。
	 *
	 * @throws Exception 构造字节失败
	 */
	@Test
	public void testDocxTruncatedThrows() throws Exception {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> new DocxDocumentParser().parse(truncated(goodDocx(), 0.5), "bad.docx", meta()));
		assertTrue(e.getMessage(), e.getMessage().contains("解析 DOCX 失败"));
	}

	/**
	 * XLSX：截断的 OOXML 字节触发 IOException，包装为 IllegalStateException。
	 *
	 * @throws Exception 构造字节失败
	 */
	@Test
	public void testXlsxTruncatedThrows() throws Exception {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> new XlsxDocumentParser().parse(truncated(goodXlsx(), 0.5), "bad.xlsx", meta()));
		assertTrue(e.getMessage(), e.getMessage().contains("解析 XLSX 失败"));
	}

	/**
	 * PPTX：截断的 OOXML 字节触发 IOException，包装为 IllegalStateException。
	 *
	 * @throws Exception 构造字节失败
	 */
	@Test
	public void testPptxTruncatedThrows() throws Exception {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> new PptxDocumentParser().parse(truncated(goodPptx(), 0.5), "bad.pptx", meta()));
		assertTrue(e.getMessage(), e.getMessage().contains("解析 PPTX 失败"));
	}

	/**
	 * XLSX：无工作表的空工作簿返回空列表。
	 *
	 * @throws Exception 写工作簿失败
	 */
	@Test
	public void testXlsxNoSheetsEmpty() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			wb.write(out);
		}
		List<Document> docs = new XlsxDocumentParser().parse(out.toByteArray(), "empty.xlsx", meta());
		assertTrue(docs.isEmpty());
	}

	/**
	 * PPTX：无幻灯片的空演示返回空列表。
	 *
	 * @throws Exception 写演示失败
	 */
	@Test
	public void testPptxNoSlidesEmpty() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XMLSlideShow show = new XMLSlideShow()) {
			show.write(out);
		}
		List<Document> docs = new PptxDocumentParser().parse(out.toByteArray(), "empty.pptx", meta());
		assertTrue(docs.isEmpty());
	}

	/**
	 * 解析器扩展名集合。
	 */
	@Test
	public void testExtensions() {
		assertTrue(new DocxDocumentParser().extensions().contains(".docx"));
		assertTrue(new XlsxDocumentParser().extensions().contains(".xlsx"));
		assertTrue(new PptxDocumentParser().extensions().contains(".pptx"));
		assertEquals(1, new DocxDocumentParser().extensions().size());
	}
}
