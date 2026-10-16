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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.function.Consumer;

import org.junit.Test;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.AudioClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.ImageClient;
import com.sure.ai.client.VideoClient;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
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
 * {@link AsyncClients} 工厂方法与各异步客户端同步委托方法测试。
 *
 * @author sureai
 * @since 1.4.0
 */
public class AsyncClientsTest {

	/** 空实现 AiClient。 */
	private static class FakeAi implements AiClient {
		@Override
		public String name() {
			return "fake";
		}

		@Override
		public ChatResponse chat(ChatRequest request) {
			return null;
		}

		@Override
		public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		}

		@Override
		public void close() {
		}
	}

	/** 空实现 AudioClient。 */
	private static final class FakeAudio implements AudioClient {
		@Override
		public TtsResponse synthesize(TtsRequest request) {
			return null;
		}

		@Override
		public SttResponse transcribe(SttRequest request) {
			return null;
		}
	}

	/** 空实现 EmbeddingClient。 */
	private static final class FakeEmbedding implements EmbeddingClient {
		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			return null;
		}
	}

	/** 空实现 ImageClient。 */
	private static final class FakeImage implements ImageClient {
		@Override
		public ImageResponse generate(ImageRequest request) {
			return null;
		}
	}

	/** 空实现 VideoClient。 */
	private static final class FakeVideo implements VideoClient {
		@Override
		public VideoResponse generate(VideoRequest request) {
			return null;
		}
	}

	/** 所有工厂重载均可调用。 */
	@Test
	public void testFactories() {
		assertNotNull(AsyncClients.chat(new FakeAi()));
		assertNotNull(AsyncClients.chat(new FakeAi(), Runnable::run));
		assertNotNull(AsyncClients.embed(new FakeEmbedding()));
		assertNotNull(AsyncClients.embed(new FakeEmbedding(), Runnable::run));
		assertNotNull(AsyncClients.image(new FakeImage()));
		assertNotNull(AsyncClients.image(new FakeImage(), Runnable::run));
		assertNotNull(AsyncClients.video(new FakeVideo()));
		assertNotNull(AsyncClients.video(new FakeVideo(), Runnable::run));
		assertNotNull(AsyncClients.audio(new FakeAudio()));
		assertNotNull(AsyncClients.audio(new FakeAudio(), Runnable::run));
	}

	/** 异步 Audio 客户端的同步委托方法透传。 */
	@Test
	public void testAudioSyncDelegates() {
		RecordingAudio audio = new RecordingAudio();
		AsyncAudioClient client = new AsyncAudioClient(audio);
		client.synthesize((TtsRequest) null);
		client.synthesize("m", "t", "v");
		client.transcribe((SttRequest) null);
		client.transcribe("m", new byte[] { 1 });
		assertEquals(4, audio.calls);
	}

	/** 异步 Chat 客户端的同步委托方法透传。 */
	@Test
	public void testChatSyncDelegates() {
		RecordingAi ai = new RecordingAi();
		AsyncAiClient client = new AsyncAiClient(ai);
		client.chat((ChatRequest) null);
		client.chat("m", "prompt");
		assertEquals(2, ai.calls);
	}

	/** 记录调用次数的 AiClient。 */
	private static final class RecordingAi extends FakeAi {
		int calls;

		@Override
		public ChatResponse chat(ChatRequest request) {
			this.calls++;
			return null;
		}
	}

	/** 记录调用次数的 AudioClient。 */
	private static final class RecordingAudio implements AudioClient {
		int calls;

		@Override
		public TtsResponse synthesize(TtsRequest request) {
			this.calls++;
			return null;
		}

		@Override
		public SttResponse transcribe(SttRequest request) {
			this.calls++;
			return null;
		}
	}

	/** 记录调用次数的 ImageClient。 */
	private static final class RecordingImage implements ImageClient {
		int calls;

		@Override
		public ImageResponse generate(ImageRequest request) {
			this.calls++;
			return null;
		}
	}

	/** 记录调用次数的 EmbeddingClient。 */
	private static final class RecordingEmbedding implements EmbeddingClient {
		int calls;

		@Override
		public EmbeddingResponse embed(EmbeddingRequest request) {
			this.calls++;
			return null;
		}
	}

	/** 记录调用次数的 VideoClient。 */
	private static final class RecordingVideo implements VideoClient {
		int calls;

		@Override
		public VideoResponse generate(VideoRequest request) {
			this.calls++;
			return null;
		}
	}

	/** 其余异步客户端的同步委托方法透传。 */
	@Test
	public void testOtherSyncDelegates() {
		RecordingImage img = new RecordingImage();
		new AsyncImageClient(img).generate((ImageRequest) null);
		new AsyncImageClient(img).generate("m", "p");
		assertEquals(2, img.calls);

		RecordingEmbedding emb = new RecordingEmbedding();
		new AsyncEmbeddingClient(emb).embed((EmbeddingRequest) null);
		new AsyncEmbeddingClient(emb).embed("m", "t");
		assertEquals(2, emb.calls);

		RecordingVideo vid = new RecordingVideo();
		new AsyncVideoClient(vid).generate((VideoRequest) null);
		assertEquals(1, vid.calls);
	}
}
