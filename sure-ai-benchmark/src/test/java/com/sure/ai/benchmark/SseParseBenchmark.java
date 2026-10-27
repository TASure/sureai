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

package com.sure.ai.benchmark;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.sure.ai.internal.http.SseLineReader;

/**
 * {@link SseLineReader} 流式 SSE 行解析微基准。
 *
 * <p>core 中存在 {@code internal/http/SseLineReader}（按 SSE 规范解析 data/event 行），
 * 此处用预构造的字节流模拟一段流式响应分片，纯 CPU 解析、无网络。</p>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn -pl sure-ai-benchmark exec:java -Dexec.args="SseParseBenchmark.* -wi 3 -i 5 -f 1 -tu us"
 * </pre>
 *
 * @author sureai
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class SseParseBenchmark {

	/** 预热 SSE 字节流（约 20 个 chunk 事件 + 1 个注释行）。 */
	private byte[] sseBytes;

	/**
	 * 预构造一段 SSE 响应体。
	 */
	@Setup
	public void setUp() {
		StringBuilder sb = new StringBuilder(1024);
		sb.append(": connected\n\n");
		for (int i = 0; i < 20; i++) {
			sb.append("event: completion\n")
				.append("data: {\"choices\":[{\"delta\":{\"content\":\"chunk-").append(i)
				.append("\"}}]}\n\n");
		}
		sb.append("data: [DONE]\n\n");
		this.sseBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * 解析整段 SSE 字节流，统计事件数。
	 *
	 * @return 解析到的事件数
	 */
	@Benchmark
	public int parseSseStream() {
		int[] counter = new int[1];
		SseLineReader.read(new ByteArrayInputStream(this.sseBytes),
			StandardCharsets.UTF_8, ev -> counter[0]++);
		return counter[0];
	}
}
