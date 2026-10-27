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

package {{mainPackage}};

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.sure.ai.rag.model.Document;
import com.sure.ai.rag.retriever.KeywordRetriever;
import com.sure.ai.rag.splitter.FixedSizeTextSplitter;

/**
 * 知识库问答模板最小单元测试（零真实网络，纯内存逻辑）。
 */
public class MainTest {

	@Test
	public void splitterProducesExpectedChunksWithOverlap() {
		String text = "第一段内容一二三四五六七八九十。第二段内容甲乙丙丁戊己庚辛。第三段内容壬癸子丑寅卯辰巳。";
		FixedSizeTextSplitter splitter = new FixedSizeTextSplitter(10, 3);
		List<String> chunks = splitter.split(text);
		assertTrue("文本较长时应拆出多块", chunks.size() >= 2);
		for (String chunk : chunks) {
			assertTrue("每块不超过块大小上限", chunk.length() <= 10 + 3);
			assertTrue("每块非空", !chunk.isBlank());
		}
	}

	@Test
	public void keywordRetrieverRanksExactHitFirst() {
		List<Document> docs = List.of(
			Document.of("a", "苹果是一种常见水果，富含维生素。"),
			Document.of("b", "香蕉富含钾元素，适合运动后食用。"),
			Document.of("c", "苹果派是西方常见甜点，用苹果制作。"));
		KeywordRetriever retriever = new KeywordRetriever(docs);
		List<Document> hits = retriever.retrieve("苹果", 2);
		assertEquals("含苹果的文档优先命中", "a", hits.get(0).id());
		assertTrue("命中数量不超过 topK", hits.size() <= 2);
	}

	@Test
	public void irrelevantQueryScoresZero() {
		KeywordRetriever retriever = new KeywordRetriever(
			List.of(Document.of("a", "内容一"), Document.of("b", "内容二")));
		List<KeywordRetriever.Scored> hits = retriever.retrieveWithScores("完全不相关的词xyz", 3);
		assertTrue("无关查询所有文档得分为 0（无词项重叠）", hits.stream().allMatch(h -> h.score() == 0.0));
	}
}
