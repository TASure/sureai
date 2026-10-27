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
package com.sure.ai.framework;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.framework.advisor.Advisor;
import com.sure.ai.framework.advisor.AdvisorChain;
import com.sure.ai.framework.advisor.AdvisorContext;
import com.sure.ai.framework.advisor.LoggingAdvisor;
import com.sure.ai.framework.advisor.ReflectionToolExecutor;
import com.sure.ai.framework.annotation.AiService;
import com.sure.ai.framework.annotation.Param;
import com.sure.ai.framework.annotation.Tool;
import com.sure.ai.framework.annotation.UserMessage;
import com.sure.ai.framework.cache.Builder;
import com.sure.ai.framework.cache.DefaultSemanticCache;
import com.sure.ai.framework.cache.SemanticCache;
import com.sure.ai.framework.memory.InMemoryChatMemory;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;

/**
 * framework 模块覆盖率补测（非 advisor 包）：ReflectionToolExecutor / AdvisorContext /
 * FrameworkProxy 边界 / DefaultSemanticCache / cache.Builder / InMemoryChatMemory /
 * LoggingAdvisor / Advisor 默认方法。
 *
 * <p>advisor 包内的补测见 {@code FrameworkAdvisorCoverageExtraTest}。</p>
 *
 * @author sureai
 * @since 2.6.0
 */
public class FrameworkCoverageExtraTest {

	/** 构造一个基础请求。 */
	private static ChatRequest baseRequest() {
		return ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi"))).build();
	}

	// ==================== AdvisorContext ====================

	/** response getter/setter 往返。 */
	@Test
	public void advisorContextResponseRoundTrip() {
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, null);
		assertNull(ctx.response());
		ChatResponse resp = FakeAiClient.resp("ok");
		ctx.response(resp);
		assertSame(resp, ctx.response());
	}

	/** attribute 读写。 */
	@Test
	public void advisorContextAttributeRoundTrip() {
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, null);
		assertNull(ctx.attribute("k"));
		ctx.attribute("k", "v");
		assertEquals("v", ctx.attribute("k"));
	}

	/** rebuildRequest 携带 temperature / maxTokens / topP / seed / extra 等字段。 */
	@Test
	public void advisorContextRebuildRequestCopiesAllFields() {
		ChatRequest b = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi")))
			.temperature(0.7)
			.maxTokens(100)
			.topP(0.9)
			.seed(42)
			.user("alice")
			.stop(";")
			.build();
		AdvisorContext ctx = new AdvisorContext(b, null, null);
		ctx.messages().add(ChatMessage.user("second"));
		ChatRequest rebuilt = ctx.rebuildRequest();
		assertEquals(Double.valueOf(0.7), rebuilt.temperature());
		assertEquals(Integer.valueOf(100), rebuilt.maxTokens());
		assertEquals(Double.valueOf(0.9), rebuilt.topP());
		assertEquals(Integer.valueOf(42), rebuilt.seed());
		assertEquals("alice", rebuilt.user());
		assertEquals(2, rebuilt.messages().size());
	}

	/** rebuildRequest: stop 为 List 形式。 */
	@Test
	public void advisorContextRebuildRequestStopList() {
		ChatRequest b = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi")))
			.stop(List.of("a", "b"))
			.build();
		AdvisorContext ctx = new AdvisorContext(b, null, null);
		assertNotNull(ctx.rebuildRequest().stop());
	}

	/** rebuildRequest: extra 透传。 */
	@Test
	public void advisorContextRebuildRequestExtraCopied() {
		ChatRequest b = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi")))
			.extra("k", "v").build();
		AdvisorContext ctx = new AdvisorContext(b, null, null);
		assertEquals("v", ctx.rebuildRequest().extra().get("k"));
	}

	// ==================== Advisor 默认方法 ====================

	/** Advisor 默认 around 直接透传。 */
	@Test
	public void advisorDefaultAroundProceeds() {
		Advisor advisor = new Advisor() { };
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, null);
		AdvisorChain chain = new AdvisorChain(List.of(advisor), c -> FakeAiClient.resp("default"));
		ChatResponse resp = chain.execute(ctx);
		assertEquals("default", resp.firstText());
	}

	// ==================== ReflectionToolExecutor ====================

	/** 带各种参数类型的工具接口。 */
	@AiService(model = "m")
	public interface Types {

		@UserMessage("q")
		String chat(String q);

		@Tool(description = "字符串")
		String strTool(String s);

		@Tool(description = "整型")
		int intTool(int a, Integer b);

		@Tool(description = "长整")
		long longTool(long l);

		@Tool(description = "浮点")
		double doubleTool(double d, double f);

		@Tool(description = "布尔")
		boolean boolTool(boolean b);

		@Tool(description = "对象")
		Object objTool(Object o);
	}

	/** 带 @Param 显式绑定名的工具接口。 */
	@AiService(model = "m")
	public interface Named {

		@UserMessage("q")
		String chat(String q);

		@Tool(description = "命名")
		String named(@Param("customName") String s);
	}

	/** 带自定义工具名的接口。 */
	@AiService(model = "m")
	public interface NamedTool {

		@UserMessage("q")
		String chat(String q);

		@Tool(name = "my-add", description = "加")
		int add(int a, int b);
	}

	/** 返回 null 的工具接口。 */
	@AiService(model = "m")
	public interface NullReturn {

		@UserMessage("q")
		String chat(String q);

		@Tool(description = "返回 null")
		String maybeNull(String s);
	}

	/** 带 boolean 参数的工具接口。 */
	@AiService(model = "m")
	public interface BoolTool {

		@UserMessage("q")
		String chat(String q);

		@Tool(description = "布尔")
		String run(boolean flag, String s);
	}

	/** 带 double 参数的工具接口。 */
	@AiService(model = "m")
	public interface DoubleTool {

		@UserMessage("q")
		String chat(String q);

		@Tool(description = "浮点")
		String run(double d, String s);
	}

	/** 带默认方法的服务接口。 */
	@AiService(model = "default-model")
	public interface DefaultService {

		@UserMessage("q")
		String chat(String q);

		@UserMessage("你好")
		default String greet(String name) {
			return "hello " + name;
		}
	}

	/** 未注册工具返回错误文本。 */
	@Test
	public void reflectionExecutorUnknownToolReturnsError() throws Exception {
		ReflectionToolExecutor exec = new ReflectionToolExecutor(new Object(), List.of());
		String out = exec.execute("nope", "{}");
		assertTrue(out.contains("工具未注册"));
	}

	/** 全部参数类型绑定。 */
	@Test
	public void reflectionExecutorBindsAllParamTypes() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : Types.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class)) {
				methods.add(m);
			}
		}
		Types impl = new Types() {
			@Override public String chat(String q) { return q; }
			@Override public String strTool(String s) { return "str:" + s; }
			@Override public int intTool(int a, Integer b) { return a + b; }
			@Override public long longTool(long l) { return l * 2; }
			@Override public double doubleTool(double d, double f) { return d + f; }
			@Override public boolean boolTool(boolean b) { return !b; }
			@Override public Object objTool(Object o) { return "obj:" + o; }
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		assertEquals("str:hello", exec.execute("strTool", "{\"s\":\"hello\"}"));
		assertEquals("5", exec.execute("intTool", "{\"a\":2,\"b\":3}"));
		assertEquals("20", exec.execute("longTool", "{\"l\":10}"));
		assertEquals("3.5", exec.execute("doubleTool", "{\"d\":1.5,\"f\":2.0}"));
		assertEquals("false", exec.execute("boolTool", "{\"b\":true}"));
		assertTrue(exec.execute("objTool", "{\"o\":\"x\"}").startsWith("obj:"));
	}

	/** 参数缺失时使用默认值。 */
	@Test
	public void reflectionExecutorMissingParamUsesDefault() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : Types.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class) && m.getName().equals("intTool")) {
				methods.add(m);
			}
		}
		Types impl = new Types() {
			@Override public String chat(String q) { return q; }
			@Override public String strTool(String s) { return s; }
			@Override public int intTool(int a, Integer b) { return a + (b == null ? -1 : b); }
			@Override public long longTool(long l) { return l; }
			@Override public double doubleTool(double d, double f) { return d; }
			@Override public boolean boolTool(boolean b) { return b; }
			@Override public Object objTool(Object o) { return o; }
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		assertEquals("-1", exec.execute("intTool", "{}"));
	}

	/** 非法 JSON 参数退化为空对象。 */
	@Test
	public void reflectionExecutorInvalidJsonDegradesToEmpty() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : Types.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class) && m.getName().equals("strTool")) {
				methods.add(m);
			}
		}
		Types impl = new Types() {
			@Override public String chat(String q) { return q; }
			@Override public String strTool(String s) { return "got:" + s; }
			@Override public int intTool(int a, Integer b) { return a; }
			@Override public long longTool(long l) { return l; }
			@Override public double doubleTool(double d, double f) { return d; }
			@Override public boolean boolTool(boolean b) { return b; }
			@Override public Object objTool(Object o) { return o; }
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		String out = exec.execute("strTool", "not-json");
		assertTrue(out == null || out.isEmpty() || out.contains("null"));
	}

	/** @Param 显式绑定名。 */
	@Test
	public void reflectionExecutorParamAnnotationBinding() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : Named.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class)) {
				methods.add(m);
			}
		}
		Named impl = new Named() {
			@Override public String chat(String q) { return q; }
			@Override public String named(String s) { return "got:" + s; }
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		assertEquals("got:xyz", exec.execute("named", "{\"customName\":\"xyz\"}"));
	}

	// ==================== InMemoryChatMemory ====================

	/** maxEntries<1 抛异常。 */
	@Test
	public void inMemoryChatMemoryRejectsMaxEntriesBelowOne() {
		assertThrows(RuntimeException.class, () -> new InMemoryChatMemory(0));
	}

	/** 超过窗口淘汰最旧。 */
	@Test
	public void inMemoryChatMemoryEvictsOldest() {
		InMemoryChatMemory mem = new InMemoryChatMemory(2);
		mem.add(ChatMessage.user("a"));
		mem.add(ChatMessage.user("b"));
		mem.add(ChatMessage.user("c"));
		assertEquals(2, mem.size());
		assertEquals("b", mem.history().get(0).content());
	}

	/** clear 清空。 */
	@Test
	public void inMemoryChatMemoryClear() {
		InMemoryChatMemory mem = new InMemoryChatMemory();
		mem.add(ChatMessage.user("a"));
		mem.clear();
		assertEquals(0, mem.size());
		assertTrue(mem.history().isEmpty());
	}

	/** toString 不抛异常。 */
	@Test
	public void inMemoryChatMemoryToString() {
		InMemoryChatMemory mem = new InMemoryChatMemory();
		mem.add(ChatMessage.user("a"));
		assertNotNull(mem.toString());
	}

	// ==================== cache.Builder 校验 ====================

	/** threshold 越界抛异常。 */
	@Test
	public void cacheBuilderRejectsBadThreshold() {
		Builder b = SemanticCache.builder();
		assertThrows(RuntimeException.class, () -> b.threshold(-0.1));
		assertThrows(RuntimeException.class, () -> b.threshold(1.5));
	}

	/** maxEntries<1 抛异常。 */
	@Test
	public void cacheBuilderRejectsBadMaxEntries() {
		Builder b = SemanticCache.builder();
		assertThrows(RuntimeException.class, () -> b.maxEntries(0));
	}

	/** defaultTtlMillis<=0 抛异常。 */
	@Test
	public void cacheBuilderRejectsBadTtl() {
		Builder b = SemanticCache.builder();
		assertThrows(RuntimeException.class, () -> b.defaultTtlMillis(0));
		assertThrows(RuntimeException.class, () -> b.defaultTtlMillis(-1));
	}

	/** embedder 为 null 时 build 抛异常。 */
	@Test
	public void cacheBuilderRejectsNullEmbedder() {
		Builder b = SemanticCache.builder();
		assertThrows(RuntimeException.class, () -> b.build());
	}

	/** Builder 合法值全链路。 */
	@Test
	public void cacheBuilderValidValues() {
		com.sure.ai.client.EmbeddingClient embedder = req ->
			com.sure.ai.model.EmbeddingResponse.of("e", List.of(new float[]{1f}), null);
		SemanticCache cache = SemanticCache.builder()
			.embedder(embedder)
			.threshold(0.5)
			.maxEntries(5)
			.defaultTtlMillis(60_000L)
			.model("text-embedding-v1")
			.build();
		assertNotNull(cache);
	}

	// ==================== DefaultSemanticCache 边界 ====================

	/** put ttl<=0 使用默认 TTL。 */
	@Test
	public void defaultCachePutWithNonPositiveTtlUsesDefault() {
		com.sure.ai.client.EmbeddingClient embedder = req ->
			com.sure.ai.model.EmbeddingResponse.of("e", List.of(new float[]{1f}), null);
		SemanticCache cache = SemanticCache.builder().embedder(embedder).build();
		cache.put("q", FakeAiClient.resp("x"), 0);
		assertEquals(1, ((DefaultSemanticCache) cache).size());
	}

	/** remove blank query 安全返回。 */
	@Test
	public void defaultCacheRemoveBlankQueryNoOp() {
		com.sure.ai.client.EmbeddingClient embedder = req ->
			com.sure.ai.model.EmbeddingResponse.of("e", List.of(new float[]{1f}), null);
		SemanticCache cache = SemanticCache.builder().embedder(embedder).build();
		cache.remove("  ");
		cache.remove(null);
	}

	/** LRU 淘汰：maxEntries=1 时第二个条目淘汰第一个。 */
	@Test
	public void defaultCacheLruEviction() {
		com.sure.ai.client.EmbeddingClient embedder = req ->
			com.sure.ai.model.EmbeddingResponse.of("e",
				List.of(new float[]{1f, 0f, 0f}), null);
		SemanticCache cache = SemanticCache.builder()
			.embedder(embedder).maxEntries(1).build();
		cache.put("q1", FakeAiClient.resp("r1"), 60_000);
		assertEquals(1, ((DefaultSemanticCache) cache).size());
		cache.put("q2", FakeAiClient.resp("r2"), 60_000);
		assertEquals(1, ((DefaultSemanticCache) cache).size());
	}

	/** 零向量余弦返回 0，不命中。 */
	@Test
	public void defaultCacheZeroVectorNoMatch() {
		com.sure.ai.client.EmbeddingClient embedder = req ->
			com.sure.ai.model.EmbeddingResponse.of("e",
				List.of(new float[]{0f, 0f, 0f}), null);
		SemanticCache cache = SemanticCache.builder().embedder(embedder).build();
		cache.put("q1", FakeAiClient.resp("r1"), 60_000);
		assertNull(cache.get("q2"));
	}

	// ==================== LoggingAdvisor 边界 ====================

	/** 终端返回 null 时安全降级。 */
	@Test
	public void loggingAdvisorHandlesNullResponse() {
		LoggingAdvisor advisor = new LoggingAdvisor();
		AdvisorContext ctx = new AdvisorContext(baseRequest(), null, null);
		AdvisorChain chain = new AdvisorChain(List.of(advisor), c -> null);
		assertNull(chain.execute(ctx));
	}

	// ==================== FrameworkProxy 边界 ====================

	/** toString / hashCode / equals 走 Object 方法分支。 */
	@Test
	public void frameworkProxyObjectMethods() {
		FakeAiClient client = new FakeAiClient().withText("x");
		Greeter ai = FrameworkUtil.create(Greeter.class, client);
		assertNotNull(ai.toString());
		assertTrue(ai.hashCode() != 0);
		assertTrue(ai.equals(ai));
		assertFalse(ai.equals(null));
	}

	/** Builder.advisors(null) 回退空列表。 */
	@Test
	public void frameworkBuilderAdvisorsNullBecomesEmpty() {
		FakeAiClient client = new FakeAiClient().withText("x");
		Greeter ai = FrameworkUtil.builder(Greeter.class, client).advisors(null).build();
		assertEquals("x", ai.chat("a", "b"));
	}

	/** Builder.temperature 设置。 */
	@Test
	public void frameworkBuilderTemperature() {
		FakeAiClient client = new FakeAiClient().withText("x");
		Greeter ai = FrameworkUtil.builder(Greeter.class, client).temperature(0.5).build();
		ai.chat("a", "b");
		assertEquals(Double.valueOf(0.5), client.last().temperature());
	}

	/** @AiService.temperature 在 builder 未设置时生效。 */
	@AiService(model = "temp-model", temperature = 0.3)
	interface TempService {
		@UserMessage("q")
		String chat(String q);
	}

	@Test
	public void frameworkServiceAnnotationTemperature() {
		FakeAiClient client = new FakeAiClient().withText("x");
		TempService ai = FrameworkUtil.create(TempService.class, client);
		ai.chat("hi");
		assertEquals(Double.valueOf(0.3), client.last().temperature());
	}

	// ==================== ReflectionToolExecutor 补充 ====================

	/** 带 @Tool(name="custom") 的工具用自定义名注册。 */
	@Test
	public void reflectionExecutorCustomToolName() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : NamedTool.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class)) {
				methods.add(m);
			}
		}
		NamedTool impl = new NamedTool() {
			@Override public String chat(String q) { return q; }
			@Override public int add(int a, int b) { return a + b; }
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		assertEquals("5", exec.execute("my-add", "{\"a\":2,\"b\":3}"));
	}

	/** 工具返回 null 时输出空串。 */
	@Test
	public void reflectionExecutorNullResultReturnsEmpty() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : NullReturn.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class)) {
				methods.add(m);
			}
		}
		NullReturn impl = new NullReturn() {
			@Override public String chat(String q) { return q; }
			@Override public String maybeNull(String s) { return null; }
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		assertEquals("", exec.execute("maybeNull", "{\"s\":\"x\"}"));
	}

	/** 空字符串 argumentsJson 退化为空对象。 */
	@Test
	public void reflectionExecutorBlankArgsDegradesToEmpty() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : Named.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class)) {
				methods.add(m);
			}
		}
		Named impl = new Named() {
			@Override public String chat(String q) { return q; }
			@Override public String named(String s) { return "got:" + s; }
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		// 空 args → s 缺失 → null
		assertTrue(exec.execute("named", "").contains("null"));
		assertTrue(exec.execute("named", null).contains("null"));
	}

	// ==================== AdvisorContext rebuild 全字段 ====================

	/** rebuildRequest 携带 toolChoice / penalties。 */
	@Test
	public void advisorContextRebuildRequestAllAdvancedFields() {
		ChatRequest b = ChatRequest.builder().model("m")
			.messages(List.of(ChatMessage.user("hi")))
			.toolChoice("auto")
			.presencePenalty(0.5)
			.frequencyPenalty(0.3)
			.reasoningEffort("high")
			.thinkingConfig(Map.of("type", "enabled"))
			.grounding(Map.of("sources", List.of("web")))
			.seed(7)
			.build();
		AdvisorContext ctx = new AdvisorContext(b, null, null);
		ChatRequest rebuilt = ctx.rebuildRequest();
		assertEquals("auto", rebuilt.toolChoice());
		assertEquals(Double.valueOf(0.5), rebuilt.presencePenalty());
		assertEquals(Double.valueOf(0.3), rebuilt.frequencyPenalty());
		assertEquals("high", rebuilt.reasoningEffort());
		assertNotNull(rebuilt.thinkingConfig());
		assertNotNull(rebuilt.grounding());
	}

	// ==================== ReflectionToolExecutor 缺省值覆盖 ====================

	/** 缺失的 boolean 参数使用 false 缺省值。 */
	@Test
	public void reflectionExecutorMissingBooleanParamUsesFalse() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : BoolTool.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class)) {
				methods.add(m);
			}
		}
		BoolTool impl = new BoolTool() {
			@Override public String chat(String q) { return q; }
			@Override public String run(boolean flag, String s) {
				return "flag=" + flag + ",s=" + s;
			}
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		assertEquals("flag=false,s=null", exec.execute("run", "{}"));
	}

	/** 缺失的 double 参数使用 0d 缺省值。 */
	@Test
	public void reflectionExecutorMissingDoubleParamUsesZero() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : DoubleTool.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class)) {
				methods.add(m);
			}
		}
		DoubleTool impl = new DoubleTool() {
			@Override public String chat(String q) { return q; }
			@Override public String run(double d, String s) {
				return "d=" + d + ",s=" + s;
			}
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		assertEquals("d=0.0,s=null", exec.execute("run", "{}"));
	}

	/** JSON 类型不匹配时绑定失败返回错误。 */
	@Test
	public void reflectionExecutorTypeMismatchReturnsBindError() throws Exception {
		List<java.lang.reflect.Method> methods = new ArrayList<>();
		for (java.lang.reflect.Method m : Types.class.getMethods()) {
			if (m.isAnnotationPresent(Tool.class) && m.getName().equals("intTool")) {
				methods.add(m);
			}
		}
		Types impl = new Types() {
			@Override public String chat(String q) { return q; }
			@Override public String strTool(String s) { return s; }
			@Override public int intTool(int a, Integer b) { return a + b; }
			@Override public long longTool(long l) { return l; }
			@Override public double doubleTool(double d, double f) { return d; }
			@Override public boolean boolTool(boolean b) { return b; }
			@Override public Object objTool(Object o) { return o; }
		};
		ReflectionToolExecutor exec = new ReflectionToolExecutor(impl, methods);
		// a 是 int，传字符串 "abc" → getInt 抛 NumberFormatException → bind 失败
		String out = exec.execute("intTool", "{\"a\":\"abc\",\"b\":3}");
		assertTrue(out.contains("绑定失败"));
	}

	/** FrameworkUtil 私有构造器通过反射调用（应抛 AssertionError）。 */
	@Test
	public void frameworkUtilPrivateConstructorInvocable() throws Exception {
		java.lang.reflect.Constructor<FrameworkUtil> ctor =
			FrameworkUtil.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, () -> ctor.newInstance());
	}

	/** 代理调用接口默认方法走 InvocationHandler.invokeDefault。 */
	@Test
	public void frameworkProxyDefaultMethod() {
		FakeAiClient client = new FakeAiClient().withText("x");
		DefaultService svc = FrameworkUtil.create(DefaultService.class, client);
		assertEquals("hello world", svc.greet("world"));
	}

	/** 空向量 embedder 返回空数组时 get 直接返回 null。 */
	@Test
	public void defaultCacheEmptyVectorReturnsNull() {
		com.sure.ai.client.EmbeddingClient embedder = req ->
			com.sure.ai.model.EmbeddingResponse.of("e", List.of(new float[]{}), null);
		SemanticCache cache = SemanticCache.builder().embedder(embedder).build();
		assertNull(cache.get("anything"));
	}

	/** void 返回类型的接口方法在创建期即抛异常。 */
	@Test
	public void frameworkProxyVoidReturnTypeThrows() {
		FakeAiClient client = new FakeAiClient().withText("x");
		assertThrows(com.sure.ai.exception.AiException.class,
			() -> FrameworkUtil.create(VoidService.class, client));
	}

	/** 带 void 方法的接口（用于触发 void 返回类型校验）。 */
	@AiService(model = "void-model")
	public interface VoidService {
		@UserMessage("q")
		void chat(String q);
	}

	/** 基础服务接口。 */
	@AiService(model = "test-model")
	public interface Greeter {
		@UserMessage("你是一个{role}助手")
		String chat(String role, String q);
	}
}
