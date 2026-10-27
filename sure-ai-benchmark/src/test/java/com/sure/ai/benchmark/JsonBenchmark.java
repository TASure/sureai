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

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;

/**
 * {@link Json} / {@link JsonObject} / {@link JsonArray} 热点路径微基准。
 *
 * <p>运行方式（不触发网络、不读外部资源）：</p>
 * <pre>
 * mvn -pl sure-ai-benchmark -am test-compile
 * mvn -pl sure-ai-benchmark exec:java \
 *     -Dexec.args="JsonBenchmark.* -wi 3 -i 5 -f 1 -tu us"
 * </pre>
 *
 * <p>默认 {@code mvn verify} 因 surefire skipTests=true 不会执行本类。</p>
 *
 * @author sureai
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class JsonBenchmark {

	/** 中等复杂度 JSON 文本（预热/解析用）。 */
	private String mediumJson;

	/**
	 * 预热数据：构造一段中等复杂度 JSON 字符串。
	 */
	@Setup
	public void setUp() {
		StringBuilder sb = new StringBuilder(512);
		sb.append("{\"id\":\"chatcmpl-bench\",\"model\":\"gpt-4o\",\"choices\":[");
		for (int i = 0; i < 4; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append("{\"index\":").append(i)
				.append(",\"delta\":{\"role\":\"assistant\",\"content\":\"chunk-").append(i).append("\"},")
				.append("\"finish_reason\":").append(i == 3 ? "\"stop\"" : "null").append('}');
		}
		sb.append("],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34,\"total_tokens\":46}}");
		this.mediumJson = sb.toString();
	}

	/**
	 * 构造含嵌套字段的 JsonObject 并序列化为字符串。
	 *
	 * @return 序列化结果（防止死代码消除）
	 */
	@Benchmark
	public String jsonObjectSerialize() {
		JsonObject root = Json.object();
		root.put("id", "chatcmpl-bench");
		root.put("model", "gpt-4o");
		root.put("stream", true);
		JsonObject usage = Json.object();
		usage.put("prompt_tokens", 12);
		usage.put("completion_tokens", 34);
		usage.put("total_tokens", 46);
		root.put("usage", usage);
		JsonArray choices = Json.array();
		for (int i = 0; i < 4; i++) {
			JsonObject c = Json.object();
			c.put("index", i);
			c.put("finish_reason", i == 3 ? "stop" : null);
			choices.add(c);
		}
		root.put("choices", choices);
		return root.toString();
	}

	/**
	 * 解析一段中等复杂度 JSON 字符串。
	 *
	 * @return 解析结果根对象
	 */
	@Benchmark
	public Object jsonObjectParse() {
		return Json.parse(this.mediumJson);
	}

	/**
	 * 构造含 100 个元素的 JsonArray。
	 *
	 * @return 数组大小
	 */
	@Benchmark
	public int jsonArrayBuild() {
		JsonArray arr = Json.array();
		for (int i = 0; i < 100; i++) {
			JsonObject o = Json.object();
			o.put("idx", i);
			o.put("label", "item-" + i);
			o.put("active", (i & 1) == 0);
			arr.add(o);
		}
		return arr.size();
	}
}
