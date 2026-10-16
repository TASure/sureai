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

import com.sure.ai.ingest.DocumentLoader;
import com.sure.ai.ingest.spi.DocumentParser;
import com.sure.ai.rag.model.Document;

/**
 * POI 解析器测试：内存构造 docx/xlsx/pptx 字节再解析，零真实文件/网络。
 */
public class PoiParsersTest {

	/**
	 * DOCX：内存写一段落再解析。
	 *
	 * @throws Exception 写文档失败
	 */
	@Test
	public void testDocx() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XWPFDocument doc = new XWPFDocument()) {
			XWPFParagraph p = doc.createParagraph();
			p.createRun().setText("Hello from docx");
			doc.write(out);
		}
		List<Document> docs = new DocxDocumentParser().parse(out.toByteArray(), "a.docx", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("Hello from docx"));
		assertEquals("docx", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
	}

	/**
	 * XLSX：内存写一个单元格再解析。
	 *
	 * @throws Exception 写表格失败
	 */
	@Test
	public void testXlsx() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			XSSFSheet sheet = wb.createSheet("Sheet1");
			Row row = sheet.createRow(0);
			row.createCell(0).setCellValue("alpha");
			row.createCell(1).setCellValue("beta");
			wb.write(out);
		}
		List<Document> docs = new XlsxDocumentParser().parse(out.toByteArray(), "a.xlsx", meta());
		assertEquals(1, docs.size());
		String text = docs.get(0).text();
		assertTrue(text, text.contains("alpha"));
		assertTrue(text, text.contains("beta"));
		assertEquals("xlsx", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
	}

	/**
	 * PPTX：内存写一张幻灯片文本再解析。
	 *
	 * @throws Exception 写幻灯片失败
	 */
	@Test
	public void testPptx() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XMLSlideShow ppt = new XMLSlideShow()) {
			XSLFSlide slide = ppt.createSlide();
			slide.createTextBox().setText("slide title text");
			ppt.write(out);
		}
		List<Document> docs = new PptxDocumentParser().parse(out.toByteArray(), "a.pptx", meta());
		assertEquals(1, docs.size());
		assertTrue(docs.get(0).text().contains("slide title text"));
		assertEquals("pptx", docs.get(0).metadata().get(DocumentLoader.META_FORMAT));
	}

	/**
	 * all() 工厂返回三个解析器。
	 */
	@Test
	public void testFactoryAll() {
		List<DocumentParser> all = PoiParsers.all();
		assertEquals(3, all.size());
		assertTrue(PoiParsers.docx().extensions().contains(".docx"));
		assertTrue(PoiParsers.xlsx().extensions().contains(".xlsx"));
		assertTrue(PoiParsers.pptx().extensions().contains(".pptx"));
	}

	/**
	 * 空内容返回空列表。
	 */
	@Test
	public void testEmpty() throws Exception {
		// 空 DOCX（无段落）
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XWPFDocument doc = new XWPFDocument()) {
			doc.write(out);
		}
		// 空文档仍含 POI 骨架，extract 给出空串
		List<Document> docs = new DocxDocumentParser().parse(out.toByteArray(), "e.docx", meta());
		assertTrue(docs.isEmpty());
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
