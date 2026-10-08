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

package com.sure.ai.rag.store.filter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * {@link FilterExpression} 翻译模糊健壮性测试（sureai v2.1.0「生产级信任」批次 1）。
 *
 * <p>零真实网络、零新依赖。方法：构造极端嵌套深度（{@code Not(Not(...))} 链）、超长值、
 * 边界值类型（null/NaN/Infinity），跨全部 6 种方言翻译器（Redis/ES/OpenSearch/Pinecone/Qdrant/Weaviate）翻译。
 * 断言原则：要么正常返回，要么业务异常（{@link IllegalArgumentException} 等记录校验异常）；
 * 严禁 {@link StackOverflowError}/{@link NullPointerException}/{@link OutOfMemoryError} 逃逸。</p>
 *
 * @author sureai
 * @since 2.1.0
 */
public class FilterTranslatorFuzzTest {

	/**
	 * 极端嵌套：任务书要求深度 1000。翻译器递归遍历树，必须不 StackOverflowError。
	 */
	@Test
	public void deepNestingAllTranslators() {
		FilterExpression deep = FilterExpression.eq("tenant", "acme");
		for (int i = 0; i < 999; i++) {
			deep = deep.not();
		}
		// And/Or 多层嵌套
		FilterExpression wide = FilterExpression.eq("a", "1");
		for (int i = 0; i < 200; i++) {
			wide = wide.and(FilterExpression.eq("b" + i, "v"), FilterExpression.gt("score" + i, 0.5));
		}
		final FilterExpression deepRef = deep;
		final FilterExpression wideRef = wide;
		forEachTranslator(t -> {
			t.translate(deepRef);
			t.translate(wideRef);
			return null;
		});
	}

	/**
	 * 超长值与边界值：1MB 标签值、NaN/Infinity 数值、含转义特殊字符的值，各翻译器不得崩。
	 */
	@Test
	public void extremeValuesAllTranslators() {
		StringBuilder mega = new StringBuilder(1024 * 1024);
		for (int i = 0; i < 1024 * 1024; i++) {
			mega.append(i % 3 == 0 ? '{' : (i % 3 == 1 ? '}' : '|'));
		}
		String longVal = mega.toString();
		forEachTranslator(t -> {
			t.translate(FilterExpression.eq("tag", longVal));
			t.translate(FilterExpression.in("tag", List.of("a|b", "c,d", "", longVal)));
			t.translate(FilterExpression.gt("num", Double.NaN));
			t.translate(FilterExpression.lt("num", Double.POSITIVE_INFINITY));
			t.translate(FilterExpression.gte("num", Long.MIN_VALUE));
			t.translate(FilterExpression.lte("num", Long.MAX_VALUE));
			t.translate(FilterExpression.ne("tag", true));
			t.translate(FilterExpression.eq("num", -0.0d));
			return null;
		});
	}

	/**
	 * null 输入与空表达式：翻译器对 null filter 应容忍（Redis 返回 null）。
	 */
	@Test
	public void nullAndEmptyInput() {
		forEachTranslator(t -> {
			t.translate(null);
			return null;
		});
	}

	/** 跨全部 6 种方言翻译器执行同一动作，包裹 JVM 崩溃断言。 */
	private static void forEachTranslator(java.util.function.Function<FilterTranslator<?>, Object> action) {
		List<FilterTranslator<?>> translators = List.of(
			new RedisFilterTranslator(Set.of("num")),
			new ElasticsearchFilterTranslator(),
			new OpenSearchFilterTranslator(),
			new PineconeFilterTranslator(),
			new QdrantFilterTranslator(),
			new WeaviateFilterTranslator());
		for (FilterTranslator<?> t : translators) {
			assertNotNull(t);
			try {
				action.apply(t);
			} catch (IllegalArgumentException | IllegalStateException expected) {
				// 记录构造/方言不支持的业务异常：允许
			} catch (StackOverflowError | OutOfMemoryError | NullPointerException
					| ArrayIndexOutOfBoundsException e) {
				fail("FilterTranslator(" + t.getClass().getSimpleName()
					+ ") 触发 JVM 级崩溃 " + e.getClass().getName());
			}
		}
	}
}
