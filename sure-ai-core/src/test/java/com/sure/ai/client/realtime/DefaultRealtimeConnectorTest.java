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

package com.sure.ai.client.realtime;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link DefaultRealtimeConnector} 测试：仅覆盖构造与建连失败路径，零外部依赖。
 *
 * @author sureai
 * @since 0.2.0
 */
public class DefaultRealtimeConnectorTest {

	/** 空头构造不抛异常。 */
	@Test
	public void testNullHeaders() {
		DefaultRealtimeConnector c = new DefaultRealtimeConnector(null);
		assertNotNull(c);
	}

	/** 带 header 构造。 */
	@Test
	public void testWithHeaders() {
		DefaultRealtimeConnector c = new DefaultRealtimeConnector(
			Map.of("Authorization", "Bearer k"));
		assertNotNull(c);
	}

	/** 连接到不存在的端口应抛 AiException（覆盖 catch 路径）。 */
	@Test
	public void testConnectRefusedThrows() {
		DefaultRealtimeConnector c = new DefaultRealtimeConnector(
			Map.of("Authorization", "Bearer k"));
		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public void onOpen(WebSocket webSocket) {
				webSocket.request(1);
			}

			@Override
			public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket,
					CharSequence data, boolean last) {
				webSocket.request(1);
				return null;
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				// no-op
			}
		};
		AiException ex = assertThrows(AiException.class,
			() -> c.connect(URI.create("wss://127.0.0.1:1/"), listener));
		assertTrue(ex.getMessage(), ex.getMessage().contains("websocket connect failed"));
	}
}
