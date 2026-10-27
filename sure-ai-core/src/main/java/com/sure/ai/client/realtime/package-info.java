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

/**
 * 实时语音全双工对话抽象。
 *
 * <p>基于 JDK {@link java.net.http.WebSocket}，连接逻辑抽离为可注入的
 * {@link com.sure.ai.client.realtime.RealtimeConnector}，平台子类覆盖
 * {@link com.sure.ai.client.realtime.AbstractRealtimeClient#buildUri()} 与
 * {@link com.sure.ai.client.realtime.AbstractRealtimeClient#handleMessage(String)}
 * 适配具体协议。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
package com.sure.ai.client.realtime;
