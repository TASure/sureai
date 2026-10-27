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

import java.net.URI;
import java.net.http.WebSocket;

/**
 * WebSocket 连接抽象。
 *
 * <p>从 {@link AbstractRealtimeClient} 中抽离真实建连逻辑，便于测试时注入
 * FakeConnector 返回 Fake WebSocket，实现零真实网络的事件解析测试。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public interface RealtimeConnector {

	/**
	 * 建立 WebSocket 连接。
	 *
	 * @param uri      服务端地址
	 * @param listener 消息监听器
	 * @return 已连接的 WebSocket
	 */
	WebSocket connect(URI uri, WebSocket.Listener listener);
}
