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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.sure.ai.exception.AiException;
/**
 * 模型类静态工厂与不可变性测试。
 *
 * @author sureai
 * @since 0.1.0
 */
public class ModelClassesTest {

	/** Role value/fromValue。 */
	@Test
	public void testRole() {
		assertEquals("system", Role.SYSTEM.value());
		assertEquals(Role.USER, Role.fromValue("user"));
		assertEquals(Role.ASSISTANT, Role.fromValue("assistant"));
		assertEquals(Role.TOOL, Role.fromValue("tool"));
		assertThrows(AiException.class, () -> Role.fromValue("bogus"));
	}

	/** 消息静态工厂。 */
	@Test
	public void testMessageFactories() {
		assertEquals(Role.SYSTEM, ChatMessage.system("s").role());
		assertEquals("hi", ChatMessage.user("hi").content());
		assertEquals(Role.ASSISTANT, ChatMessage.assistant("a").role());
		ChatMessage tool = ChatMessage.tool("call-1", "result");
		assertEquals(Role.TOOL, tool.role());
		assertEquals("call-1", tool.toolCallId());
		assertEquals("result", tool.content());
	}

	/** 多模态 user 消息。 */
	@Test
	public void testUserParts() {
		List<MessagePart> parts = List.of(TextPart.of("t"), ImagePart.ofUrl("http://x/y.png"));
		ChatMessage m = ChatMessage.user(parts);
		assertEquals(2, m.parts().size());
		assertEquals("text", m.parts().get(0).type());
		assertEquals("image_url", m.parts().get(1).type());
	}

	/** ImagePart base64 解析。 */
	@Test
	public void testImageBase64() {
		ImagePart p = ImagePart.ofBase64("QQ==", "image/png");
		assertEquals("data:image/png;base64,QQ==", p.resolvedUrl());
	}

	/** 不可变性：构造后修改入参集合不影响消息。 */
	@Test
	public void testImmutability() {
		List<ToolCall> calls = new ArrayList<>();
		calls.add(ToolCall.of("id1", "fn", "{}"));
		ChatMessage m = ChatMessage.assistant(calls);
		calls.add(ToolCall.of("id2", "fn2", "{}"));
		assertEquals(1, m.toolCalls().size());
		assertThrows(UnsupportedOperationException.class, () -> m.toolCalls().add(ToolCall.of("x", "y", "{}")));
	}

	/** TokenUsage。 */
	@Test
	public void testUsage() {
		TokenUsage u = TokenUsage.of(10, 5, 15);
		assertEquals(10, u.promptTokens());
		assertEquals(5, u.completionTokens());
		assertEquals(15, u.totalTokens());
	}

	/** Choice/ChatResponse。 */
	@Test
	public void testChoiceAndResponse() {
		ChatMessage msg = ChatMessage.assistant("hello");
		Choice c = Choice.of(0, msg, "stop");
		assertEquals(0, c.index());
		assertEquals("stop", c.finishReason());
		ChatResponse r = ChatResponse.of("id1", "gpt", List.of(c), TokenUsage.of(1, 2, 3), "{}");
		assertEquals("hello", r.firstText());
		assertEquals("id1", r.id());
		assertTrue(r.choices().size() == 1);
	}

	/** ToolCall/ToolFunction/ToolSpec。 */
	@Test
	public void testToolRecords() {
		ToolCall tc = ToolCall.of("id", "name", "{}");
		assertEquals("id", tc.id());
		ToolFunction fn = ToolFunction.of("n", "d", "{}");
		assertEquals("n", fn.name());
		ToolSpec spec = ToolSpec.of(fn);
		assertEquals(fn, spec.function());
	}

	/** 嵌入模型。 */
	@Test
	public void testEmbeddingModels() {
		EmbeddingRequest req = EmbeddingRequest.of("m", List.of("a", "b"));
		assertEquals(2, req.input().size());
		float[] v = new float[] { 1.0f, 2.0f };
		EmbeddingResponse resp = EmbeddingResponse.of("m", List.of(v), TokenUsage.of(1, 0, 1));
		assertEquals(1, resp.embeddings().size());
		assertEquals(1, resp.usage().promptTokens());
		assertEquals(2, resp.embeddings().get(0).length);
	}

	/** ChatStreamChunk。 */
	@Test
	public void testChunk() {
		ChatStreamChunk ch = ChatStreamChunk.of("id", Role.ASSISTANT, "hi", null, null);
		assertEquals("hi", ch.deltaText());
		assertEquals(Role.ASSISTANT, ch.role());
	}

	/** ImageRequest builder：必填校验与全字段。 */
	@Test
	public void testImageRequestBuilder() {
		ImageRequest req = ImageRequest.builder()
			.model("dall-e-3").prompt("a cat").n(2).size("1024x1024")
			.quality("hd").style("vivid").responseFormat("url").user("u")
			.extra("k", "v").build();
		assertEquals("dall-e-3", req.model());
		assertEquals("a cat", req.prompt());
		assertEquals(Integer.valueOf(2), req.n());
		assertEquals("1024x1024", req.size());
		assertEquals("hd", req.quality());
		assertEquals("vivid", req.style());
		assertEquals("url", req.responseFormat());
		assertEquals("u", req.user());
		assertEquals("v", req.extra().get("k"));
	}

	/** ImageRequest.of 便捷构造。 */
	@Test
	public void testImageRequestOf() {
		ImageRequest req = ImageRequest.of("m", "p");
		assertEquals("m", req.model());
		assertEquals("p", req.prompt());
		assertNull(req.n());
	}

	/** ImageRequest 必填校验：model 为空抛异常。 */
	@Test
	public void testImageRequestValidation() {
		assertThrows(Exception.class, () -> ImageRequest.builder().prompt("p").build());
		assertThrows(Exception.class, () -> ImageRequest.builder().model("m").build());
	}

	/** ImageResult 工厂方法。 */
	@Test
	public void testImageResult() {
		ImageResult r1 = ImageResult.of("http://x", "b64", "revised");
		assertEquals("http://x", r1.url());
		assertEquals("b64", r1.b64Json());
		assertEquals("revised", r1.revisedPrompt());
		ImageResult r2 = ImageResult.ofUrl("http://y");
		assertEquals("http://y", r2.url());
		assertNull(r2.b64Json());
		ImageResult r3 = ImageResult.ofB64("abc");
		assertEquals("abc", r3.b64Json());
		assertNull(r3.url());
	}

	/** ImageResponse：of/firstUrl/firstB64/空列表防御性拷贝。 */
	@Test
	public void testImageResponse() {
		ImageResult r = ImageResult.ofUrl("http://z");
		ImageResponse resp = ImageResponse.of(123L, List.of(r), "{}");
		assertEquals(123L, resp.created());
		assertEquals(1, resp.data().size());
		assertEquals("http://z", resp.firstUrl());
		assertNull(resp.firstB64());
		ImageResponse empty = ImageResponse.of(0, null, null);
		assertTrue(empty.data().isEmpty());
		assertNull(empty.firstUrl());
	}

	// ==================== 视频模型 ====================

	/** VideoRequest builder：必填校验与全字段。 */
	@Test
	public void testVideoRequestBuilder() {
		VideoRequest req = VideoRequest.builder()
			.model("sora-2").prompt("a cat").duration(8).size("1280x720")
			.ratio("16:9").n(1).firstFrameImageUrl("http://f.png").lastFrameImageUrl("http://l.png")
			.negativePrompt("ugly").seed(42).withAudio(true).resolution("720p")
			.extra("k", "v").build();
		assertEquals("sora-2", req.model());
		assertEquals("a cat", req.prompt());
		assertEquals(Integer.valueOf(8), req.duration());
		assertEquals("1280x720", req.size());
		assertEquals("16:9", req.ratio());
		assertEquals(Integer.valueOf(1), req.n());
		assertEquals("http://f.png", req.firstFrameImageUrl());
		assertEquals("http://l.png", req.lastFrameImageUrl());
		assertEquals("ugly", req.negativePrompt());
		assertEquals(Integer.valueOf(42), req.seed());
		assertEquals(Boolean.TRUE, req.withAudio());
		assertEquals("720p", req.resolution());
		assertEquals("v", req.extra().get("k"));
	}

	/** VideoRequest.of 便捷构造与必填校验。 */
	@Test
	public void testVideoRequestOfAndValidation() {
		VideoRequest req = VideoRequest.of("m", "p");
		assertEquals("m", req.model());
		assertEquals("p", req.prompt());
		assertNull(req.duration());
		assertThrows(Exception.class, () -> VideoRequest.builder().prompt("p").build());
		assertThrows(Exception.class, () -> VideoRequest.builder().model("m").build());
	}

	/** VideoResult 工厂方法。 */
	@Test
	public void testVideoResult() {
		VideoResult r1 = VideoResult.of("http://v.mp4", "http://cover.jpg", "b64", "SUCCEEDED", "revised");
		assertEquals("http://v.mp4", r1.url());
		assertEquals("http://cover.jpg", r1.coverImageUrl());
		assertEquals("b64", r1.b64Json());
		assertEquals("SUCCEEDED", r1.taskStatus());
		assertEquals("revised", r1.revisedPrompt());
		VideoResult r2 = VideoResult.ofUrl("http://v2.mp4");
		assertEquals("http://v2.mp4", r2.url());
		assertNull(r2.coverImageUrl());
		VideoResult r3 = VideoResult.ofUrl("http://v3.mp4", "http://c3.jpg");
		assertEquals("http://c3.jpg", r3.coverImageUrl());
	}

	/** VideoResponse：of/firstUrl/firstCoverUrl/空列表防御性拷贝。 */
	@Test
	public void testVideoResponse() {
		VideoResult r = VideoResult.ofUrl("http://v.mp4", "http://c.jpg");
		VideoResponse resp = VideoResponse.of(456L, List.of(r), "{}");
		assertEquals(456L, resp.created());
		assertEquals(1, resp.data().size());
		assertEquals("http://v.mp4", resp.firstUrl());
		assertEquals("http://c.jpg", resp.firstCoverUrl());
		VideoResponse empty = VideoResponse.of(0, null, null);
		assertTrue(empty.data().isEmpty());
		assertNull(empty.firstUrl());
		assertNull(empty.firstCoverUrl());
	}

	// ==================== TTS 模型 ====================

	/** TtsRequest builder：必填校验与全字段。 */
	@Test
	public void testTtsRequestBuilder() {
		TtsRequest req = TtsRequest.builder()
			.model("tts-1").input("hello").voice("alloy")
			.responseFormat("mp3").speed(1.5).volume(80.0).pitch(1.2)
			.sampleRate(24000).instructions("speak slowly").extra("k", "v").build();
		assertEquals("tts-1", req.model());
		assertEquals("hello", req.input());
		assertEquals("alloy", req.voice());
		assertEquals("mp3", req.responseFormat());
		assertEquals(Double.valueOf(1.5), req.speed());
		assertEquals(Double.valueOf(80.0), req.volume());
		assertEquals(Double.valueOf(1.2), req.pitch());
		assertEquals(Integer.valueOf(24000), req.sampleRate());
		assertEquals("speak slowly", req.instructions());
		assertEquals("v", req.extra().get("k"));
	}

	/** TtsRequest.of 便捷构造与必填校验。 */
	@Test
	public void testTtsRequestOfAndValidation() {
		TtsRequest req = TtsRequest.of("m", "t", "v");
		assertEquals("m", req.model());
		assertEquals("t", req.input());
		assertEquals("v", req.voice());
		assertThrows(Exception.class, () -> TtsRequest.builder().input("t").voice("v").build());
		assertThrows(Exception.class, () -> TtsRequest.builder().model("m").voice("v").build());
		assertThrows(Exception.class, () -> TtsRequest.builder().model("m").input("t").build());
	}

	/** TtsResponse 工厂方法与 audioLength。 */
	@Test
	public void testTtsResponse() {
		byte[] audio = new byte[]{1, 2, 3};
		TtsResponse r1 = TtsResponse.ofAudio(audio, "mp3");
		assertEquals(3, r1.audioLength());
		assertEquals("mp3", r1.format());
		assertNull(r1.url());
		// 防御性拷贝：修改原数组不影响响应
		audio[0] = 99;
		assertEquals(1, r1.audio()[0]);
		TtsResponse r2 = TtsResponse.ofUrl("http://a.mp3", "mp3", "{}");
		assertEquals("http://a.mp3", r2.url());
		assertEquals(0, r2.audioLength());
		assertNull(r2.audio());
	}

	// ==================== STT 模型 ====================

	/** SttRequest builder：必填校验与全字段。 */
	@Test
	public void testSttRequestBuilder() {
		byte[] audio = "fake".getBytes();
		SttRequest req = SttRequest.builder()
			.model("whisper-1").audioData(audio).fileName("test.mp3")
			.contentType("audio/mpeg").language("en").responseFormat("verbose_json")
			.temperature(0.5).prompt("custom").extra("k", "v").build();
		assertEquals("whisper-1", req.model());
		assertEquals(4, req.audioData().length);
		assertEquals("test.mp3", req.fileName());
		assertEquals("audio/mpeg", req.contentType());
		assertEquals("en", req.language());
		assertEquals("verbose_json", req.responseFormat());
		assertEquals(Double.valueOf(0.5), req.temperature());
		assertEquals("custom", req.prompt());
		assertEquals("v", req.extra().get("k"));
		// 防御性拷贝
		audio[0] = 99;
		assertEquals('f', req.audioData()[0]);
	}

	/** SttRequest.of 便捷构造与必填校验。 */
	@Test
	public void testSttRequestOfAndValidation() {
		byte[] audio = "x".getBytes();
		SttRequest req = SttRequest.of("m", audio);
		assertEquals("m", req.model());
		assertEquals(1, req.audioData().length);
		assertThrows(Exception.class, () -> SttRequest.builder().audioData(audio).build());
		assertThrows(Exception.class, () -> SttRequest.builder().model("m").build());
	}

	/** SttResponse 工厂方法与字段。 */
	@Test
	public void testSttResponse() {
		List<Word> words = List.of(Word.of("hello", 0.0, 1.0));
		List<Segment> segments = List.of(Segment.of(0, 0.0, 2.0, "hello world", words));
		SttResponse resp = SttResponse.of("hello world", "en", 2.0, segments, words, "{}");
		assertEquals("hello world", resp.text());
		assertEquals("en", resp.language());
		assertEquals(Double.valueOf(2.0), resp.duration());
		assertEquals(1, resp.segments().size());
		assertEquals(1, resp.words().size());
		assertEquals("hello", resp.words().get(0).word());
		SttResponse textOnly = SttResponse.ofText("hi");
		assertEquals("hi", textOnly.text());
		assertTrue(textOnly.segments().isEmpty());
		assertTrue(textOnly.words().isEmpty());
	}

	/** Word/Segment 静态工厂。 */
	@Test
	public void testWordAndSegment() {
		Word w = Word.of("test", 0.5, 1.5);
		assertEquals("test", w.word());
		assertEquals(0.5, w.start(), 0.001);
		assertEquals(1.5, w.end(), 0.001);
		Segment s = Segment.of(1, 0.0, 3.0, "segment text");
		assertEquals(1, s.id());
		assertEquals("segment text", s.text());
		assertTrue(s.words().isEmpty());
	}

	// ==================== P1：缓存控制与文档片段 ====================

	/** CacheControl.ephemeral 固定类型。 */
	@Test
	public void testCacheControl() {
		CacheControl cc = CacheControl.ephemeral();
		assertEquals("ephemeral", cc.type());
	}

	/** TextPart：默认无缓存控制，ofWithCache 携带缓存控制。 */
	@Test
	public void testTextPartCache() {
		TextPart plain = TextPart.of("hello");
		assertEquals("hello", plain.text());
		assertNull(plain.cacheControl());
		assertEquals("text", plain.type());
		TextPart cached = TextPart.ofWithCache("prompt", CacheControl.ephemeral());
		assertEquals("prompt", cached.text());
		assertEquals("ephemeral", cached.cacheControl().type());
	}

	/** DocumentPart：base64 与 fileId 两种工厂。 */
	@Test
	public void testDocumentPart() {
		DocumentPart b64 = DocumentPart.ofBase64("contract.pdf", "application/pdf", "AAAA");
		assertEquals("document", b64.type());
		assertEquals("contract.pdf", b64.name());
		assertEquals("application/pdf", b64.mimeType());
		assertEquals("AAAA", b64.data());
		assertNull(b64.fileId());
		DocumentPart fid = DocumentPart.ofFileId("file-123");
		assertEquals("document", fid.type());
		assertEquals("file-123", fid.fileId());
		assertNull(fid.data());
	}

	// ==================== P1：重排模型 ====================

	/** RerankRequest builder：全字段与必填校验。 */
	@Test
	public void testRerankRequestBuilder() {
		RerankRequest req = RerankRequest.builder()
			.model("bge-reranker").query("q").documents(List.of("a", "b"))
			.topN(3).extra("k", "v").build();
		assertEquals("bge-reranker", req.model());
		assertEquals("q", req.query());
		assertEquals(2, req.documents().size());
		assertEquals(Integer.valueOf(3), req.topN());
		assertEquals("v", req.extra().get("k"));
		// addDocument 链式追加
		RerankRequest r2 = RerankRequest.builder().model("m").query("q")
			.addDocument("x").addDocument("y").build();
		assertEquals(2, r2.documents().size());
	}

	/** RerankRequest 必填校验。 */
	@Test
	public void testRerankRequestValidation() {
		assertThrows(Exception.class, () -> RerankRequest.builder().query("q").documents(List.of("a")).build());
		assertThrows(Exception.class, () -> RerankRequest.builder().model("m").documents(List.of("a")).build());
		assertThrows(Exception.class, () -> RerankRequest.builder().model("m").query("q").build());
	}

	/** RerankResult / RerankResponse。 */
	@Test
	public void testRerankModels() {
		RerankResult r = RerankResult.of(1, 0.92, "doc text", "{}");
		assertEquals(1, r.index());
		assertEquals(0.92, r.relevanceScore(), 1e-9);
		assertEquals("doc text", r.document());
		RerankResponse resp = RerankResponse.of("bge", List.of(r), "{raw}");
		assertEquals("bge", resp.model());
		assertEquals(1, resp.results().size());
		assertEquals("{raw}", resp.rawJson());
		// 空列表防御性拷贝
		RerankResponse empty = RerankResponse.of("m", null, null);
		assertTrue(empty.results().isEmpty());
	}

	// ==================== P1：批处理模型 ====================

	/** BatchRequest builder：requests 形式。 */
	@Test
	public void testBatchRequestBuilder() {
		ChatRequest c1 = ChatRequest.builder().model("gpt").messages(ChatMessage.user("a")).build();
		ChatRequest c2 = ChatRequest.builder().model("gpt").messages(ChatMessage.user("b")).build();
		BatchRequest req = BatchRequest.builder()
			.model("gpt-4o").requests(List.of(c1, c2)).completionWindow("24h")
			.metadata("env", "test").extra("k", "v").build();
		assertEquals("gpt-4o", req.model());
		assertEquals(2, req.requests().size());
		assertEquals("24h", req.completionWindow());
		assertEquals("test", req.metadata().get("env"));
		assertEquals("v", req.extra().get("k"));
		assertNull(req.inputFileId());
	}

	/** BatchRequest：inputFileId 形式与必填校验。 */
	@Test
	public void testBatchRequestFileIdAndValidation() {
		BatchRequest req = BatchRequest.builder().model("gpt").inputFileId("file-9").build();
		assertEquals("file-9", req.inputFileId());
		assertTrue(req.requests().isEmpty());
		// 无 inputFileId 且无 requests 应抛异常
		assertThrows(Exception.class, () -> BatchRequest.builder().model("gpt").build());
		// 缺少 model 应抛异常
		assertThrows(Exception.class, () -> BatchRequest.builder().inputFileId("f").build());
	}

	/** BatchResponse：状态便捷方法与 RequestCounts。 */
	@Test
	public void testBatchResponse() {
		BatchResponse.RequestCounts counts = BatchResponse.RequestCounts.of(10, 8, 2);
		assertEquals(10, counts.total());
		assertEquals(8, counts.completed());
		assertEquals(2, counts.failed());
		BatchResponse done = BatchResponse.of("b-1", "completed", 100L, 200L, counts, null, "{}");
		assertTrue(done.isCompleted());
		assertFalse(done.isFailed());
		assertEquals(100L, done.createdAt());
		assertEquals(200L, done.completedAt());
		BatchResponse failed = BatchResponse.of("b-2", "failed", 1L, 2L, null, "boom", "{}");
		assertFalse(failed.isCompleted());
		assertTrue(failed.isFailed());
		assertEquals("boom", failed.error());
		// null requestCounts 归一化为 0
		assertEquals(0, failed.requestCounts().total());
	}
}
