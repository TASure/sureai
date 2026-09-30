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

package com.sure.ai.client.async;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.sure.ai.client.AudioClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.ImageClient;
import com.sure.ai.client.VideoClient;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * 向量 / 图像 / 视频 / 语音客户端异步装饰器单元测试（1.9.0）。
 *
 * <p>各 Fake 客户端为内存实现、零真实网络；断言异步结果与同步结果一致，并覆盖
 * {@link AsyncClients} 工厂对这四个客户端的包装。</p>
 *
 * @author sureai
 * @since 1.9.0
 */
public class AsyncMediaClientsTest {

	/** 内存向量客户端。 */
	static final class FakeEmbeddingClient implements EmbeddingClient {
		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			return EmbeddingResponse.of(request.model(), List.of(new float[] { 1f, 2f }), null);
		}
	}

	/** 内存图像客户端。 */
	static final class FakeImageClient implements ImageClient {
		@Override
		public ImageResponse generate(ImageRequest request) {
			return ImageResponse.of(1L, List.of(), "image-raw");
		}
	}

	/** 内存视频客户端。 */
	static final class FakeVideoClient implements VideoClient {
		@Override
		public VideoResponse generate(VideoRequest request) {
			return VideoResponse.of(2L, List.of(), "video-raw");
		}
	}

	/** 内存语音客户端。 */
	static final class FakeAudioClient implements AudioClient {
		@Override
		public TtsResponse synthesize(TtsRequest request) {
			return TtsResponse.ofAudio(new byte[] { 9 }, "mp3");
		}

		@Override
		public SttResponse transcribe(SttRequest request) {
			return SttResponse.ofText("rec-hello");
		}
	}

	/** embed/embedAsync 结果一致，含便捷重载。 */
	@Test
	public void embeddingAsyncMatchesSync() throws Exception {
		FakeEmbeddingClient fake = new FakeEmbeddingClient();
		AsyncEmbeddingClient async = AsyncClients.embed(fake);
		EmbeddingRequest req = EmbeddingRequest.of("emb-model", List.of("hi"));

		EmbeddingResponse sync = fake.embed(req);
		EmbeddingResponse r1 = async.embedAsync(req).get(5, TimeUnit.SECONDS);
		assertEquals(sync.model(), r1.model());
		assertArrayEquals(new float[] { 1f, 2f }, r1.embeddings().get(0), 0f);

		EmbeddingResponse r2 = async.embedAsync("emb-model", "hi").get(5, TimeUnit.SECONDS);
		assertEquals("emb-model", r2.model());
	}

	/** generate/generateAsync 结果一致。 */
	@Test
	public void imageAsyncMatchesSync() throws Exception {
		FakeImageClient fake = new FakeImageClient();
		AsyncImageClient async = AsyncClients.image(fake);
		ImageRequest req = ImageRequest.of("img-model", "a cat");

		ImageResponse sync = fake.generate(req);
		ImageResponse r1 = async.generateAsync(req).get(5, TimeUnit.SECONDS);
		assertEquals(sync.rawJson(), r1.rawJson());
		assertEquals("image-raw", r1.rawJson());

		ImageResponse r2 = async.generateAsync("img-model", "a cat").get(5, TimeUnit.SECONDS);
		assertNotNull(r2);
	}

	/** generate/generateAsync（视频）结果一致。 */
	@Test
	public void videoAsyncMatchesSync() throws Exception {
		FakeVideoClient fake = new FakeVideoClient();
		AsyncVideoClient async = AsyncClients.video(fake);
		VideoRequest req = VideoRequest.of("vid-model", "a dog running");

		VideoResponse sync = fake.generate(req);
		VideoResponse r = async.generateAsync(req).get(5, TimeUnit.SECONDS);
		assertEquals(sync.rawJson(), r.rawJson());
		assertEquals("video-raw", r.rawJson());
	}

	/** TTS synthesizeAsync 与 STT transcribeAsync 均结果一致。 */
	@Test
	public void audioSynthesizeAndTranscribeAsyncMatchSync() throws Exception {
		FakeAudioClient fake = new FakeAudioClient();
		AsyncAudioClient async = AsyncClients.audio(fake);

		TtsResponse tts = async.synthesizeAsync(TtsRequest.of("t-model", "你好", "v1"))
			.get(5, TimeUnit.SECONDS);
		assertArrayEquals(new byte[] { 9 }, tts.audio());

		TtsResponse tts2 = async.synthesizeAsync("t-model", "你好", "v1")
			.get(5, TimeUnit.SECONDS);
		assertArrayEquals(new byte[] { 9 }, tts2.audio());

		SttResponse stt = async.transcribeAsync(SttRequest.of("s-model", new byte[] { 1, 2 }))
			.get(5, TimeUnit.SECONDS);
		assertEquals("rec-hello", stt.text());

		SttResponse stt2 = async.transcribeAsync("s-model", new byte[] { 1, 2 })
			.get(5, TimeUnit.SECONDS);
		assertEquals("rec-hello", stt2.text());
	}
}
