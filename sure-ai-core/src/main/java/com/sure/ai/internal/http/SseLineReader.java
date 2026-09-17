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

package com.sure.ai.internal.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.function.Consumer;

import com.sure.ai.exception.AiException;

/**
 * SSE（Server-Sent Events）行解析器。
 *
 * <p>按 SSE 规范解析：以空行（连续换行）分隔事件；`data:` 行收集数据，多行 data 以 \n 拼接；
 * `event:` 行设置事件名；`:` 开头为注释忽略；读到流结束自然结束。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class SseLineReader {

	/** 私有构造器。 */
	private SseLineReader() {
		throw new AssertionError("No instances");
	}

	/**
	 * 读取输入流并将每个 SSE 事件投递给消费者。
	 *
	 * @param input   输入流
	 * @param charset 字符集
	 * @param handler 事件消费者
	 * @throws AiException IO 错误时抛出
	 */
	public static void read(InputStream input, Charset charset, Consumer<SseEvent> handler) {
		BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset));
		StringBuilder dataBuf = new StringBuilder();
		String eventName = null;
		boolean hasData = false;
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					dispatch(handler, eventName, dataBuf, hasData);
					dataBuf.setLength(0);
					eventName = null;
					hasData = false;
					continue;
				}
				if (line.charAt(0) == ':') {
					continue;
				}
				int colon = line.indexOf(':');
				String field = colon < 0 ? line : line.substring(0, colon);
				String value = colon < 0 ? "" : line.substring(colon + 1);
				if (value.startsWith(" ")) {
					value = value.substring(1);
				}
				switch (field) {
					case "data":
						if (hasData) {
							dataBuf.append('\n');
						}
						dataBuf.append(value);
						hasData = true;
						break;
					case "event":
						eventName = value;
						break;
					default:
						// id / retry 等字段忽略
						break;
				}
			}
			if (hasData) {
				dispatch(handler, eventName, dataBuf, hasData);
			}
		} catch (IOException ex) {
			throw new AiException("SSE read failed: " + ex.getMessage(), ex);
		}
	}

	/** 投递一个事件。 */
	private static void dispatch(Consumer<SseEvent> handler, String eventName,
			StringBuilder dataBuf, boolean hasData) {
		if (!hasData) {
			return;
		}
		handler.accept(new SseEvent(eventName, dataBuf.toString()));
	}
}
