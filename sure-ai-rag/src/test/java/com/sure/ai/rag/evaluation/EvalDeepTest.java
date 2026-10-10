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
package com.sure.ai.rag.evaluation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.rag.model.Document;

/**
 * 评估子系统边界单元测试：{@link InMemoryTraceStore} 的 null 查找/toString/of 批量构建、
 * {@link TraceSerializer} 的元数据多类型（布尔/数字/null）与文档元数据往返、
 * {@link TraceReplay} 的四种回放入口，零外部依赖。
 *
 * @author sureai
 * @since 2.6.0
 */
public class EvalDeepTest {

	private static RagTrace sample() {
		return RagTrace.builder().traceId("t1").question("q").answer("a")
				.contexts(List.of(new Document("d1", "ctx", Map.of("src", "x.pdf"))))
				.referenceAnswer("ref")
				.metadata("num", 1).metadata("flag", true)
				.build();
	}

	/** findById(null) 返回 null；toString；of 批量构建。 */
	@Test
	public void testTraceStoreEdges() {
		InMemoryTraceStore store = InMemoryTraceStore.of(List.of(sample()));
		assertNull(store.findById(null));
		assertEquals(1, store.size());
		assertTrue(store.toString().contains("size=1"));
		assertNotNull(store.findById("t1"));
	}

	/** 序列化往返：元数据多类型 + 文档元数据。 */
	@Test
	public void testSerializerMultiTypeRoundTrip() {
		String json = TraceSerializer.toJson(sample());
		RagTrace back = TraceSerializer.fromJson(json);
		assertEquals("t1", back.traceId());
		assertEquals("ref", back.referenceAnswer());
		assertEquals(1, back.contexts().size());
		assertEquals("x.pdf", back.contexts().get(0).metadata().get("src"));
		assertEquals(1.0, (Double) back.metadata().get("num"), 0.001);
		assertEquals(Boolean.TRUE, back.metadata().get("flag"));
	}

	/** 列表序列化往返。 */
	@Test
	public void testSerializerListRoundTrip() {
		String json = TraceSerializer.toJsonList(List.of(sample(), sample()));
		List<RagTrace> back = TraceSerializer.fromJsonList(json);
		assertEquals(2, back.size());
	}

	/** TraceReplay：存储回放与聚合。 */
	@Test
	public void testReplayFromStore() {
		RagMetric metric = new RagMetric() {
			@Override
			public String name() {
				return "stub";
			}

			@Override
			public double evaluate(RagTrace trace) {
				return 0.5;
			}
		};
		RagEvaluator evaluator = RagEvaluator.of(List.of(metric));
		TraceReplay replay = new TraceReplay(evaluator);
		InMemoryTraceStore store = InMemoryTraceStore.of(List.of(sample()));
		assertEquals(1, replay.replay(store).size());
		assertNotNull(replay.replayAggregate(store));
	}

	/** TraceReplay：JSON 回放与聚合。 */
	@Test
	public void testReplayFromJson() {
		RagMetric metric = new RagMetric() {
			@Override
			public String name() {
				return "stub";
			}

			@Override
			public double evaluate(RagTrace trace) {
				return 0.5;
			}
		};
		RagEvaluator evaluator = RagEvaluator.of(List.of(metric));
		TraceReplay replay = new TraceReplay(evaluator);
		String json = TraceSerializer.toJsonList(List.of(sample()));
		assertEquals(1, replay.replayFromJson(json).size());
		assertNotNull(replay.replayAggregateFromJson(json));
	}
}
