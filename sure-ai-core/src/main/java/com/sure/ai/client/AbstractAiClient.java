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

package com.sure.ai.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.sure.ai.client.observability.MetricsCollector;
import com.sure.ai.exception.AiException;
import com.sure.ai.internal.http.SseLineReader;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * HTTP 客户端抽象基类：封装 JSON POST、SSE 流式 POST、重试与错误映射。
 *
 * <p>子类需实现 {@link #applyAuth} 添加鉴权头，并可覆盖 {@link #mapError} 定制错误映射。</p>
 *
 * <p>可观测性（迭代二）：所有 HTTP 发送路径统一走 {@link RetryExecutor} 模板，
 * 内置重试事件回调、指标埋点与客户端限流。未挂载时行为与重构前完全一致。</p>
 *
 * <p>熔断（{@link RetryExecutor} 内部）：在重试循环之外再包一层——重试是单次请求内部的
 * 指数退避，熔断是跨请求的故障状态机。未配置（null）时零开销；OPEN 时不发起网络、
 * 不触发重试/指标/限流，直接快速失败。</p>
 *
 * <p>可维护性迭代（1.4.0）：本类由 682 行的上帝类按职责拆分为三个包内可见协作组件——
 * {@link RetryExecutor}（重试/熔断/限流/指标/错误映射）、{@link RequestBuilder}
 * （请求构建与鉴权/签名钩子链）、{@link MultipartBodyBuilder}（multipart 拼装）。
 * 本类保留子类契约所需的全部 protected API，行为与拆分前完全一致。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public abstract class AbstractAiClient {

	/** 日志器 */
	protected final Logger log = Logger.getLogger(getClass().getName());

	/** 配置 */
	protected final AiConfig config;

	/** HTTP 客户端 */
	protected final HttpClient httpClient;

	/** 重试/熔断/限流/指标执行器（横切关注点，包内委托）。 */
	private final RetryExecutor retryExecutor;

	/** 请求构建器（鉴权/额外头/签名钩子链，包内委托）。 */
	private final RequestBuilder requestBuilder;

	/**
	 * 构造并根据配置构建 HttpClient。
	 *
	 * @param config 配置
	 */
	protected AbstractAiClient(AiConfig config) {
		this.config = config;
		HttpClient.Builder cb = HttpClient.newBuilder()
			.connectTimeout(config.connectTimeout())
			.version(HttpClient.Version.HTTP_1_1);
		if (config.proxy() != null && !config.proxy().isBlank()) {
			cb.proxy(ProxySelector.of(parseProxy(config.proxy())));
		}
		this.httpClient = cb.build();
		this.retryExecutor = new RetryExecutor(config, this.httpClient, this.log,
			getClass().getSimpleName());
		this.requestBuilder = new RequestBuilder(this);
	}

	/** 解析 host:port 为 SocketAddress。 */
	private static InetSocketAddress parseProxy(String proxy) {
		int idx = proxy.lastIndexOf(':');
		String host = proxy.substring(0, idx);
		int port = Integer.parseInt(proxy.substring(idx + 1));
		return new InetSocketAddress(host, port);
	}

	/**
	 * 子类添加鉴权头。
	 *
	 * @param requestBuilder 请求构建器
	 * @param config        配置
	 */
	protected abstract void applyAuth(HttpRequest.Builder requestBuilder, AiConfig config);

	/**
	 * 对请求进行签名，返回需要附加的请求头。
	 *
	 * <p>默认返回空 map（无额外签名）。需要复杂签名（如 AWS SigV4、OAuth1）的子类可覆写
	 * 此方法——此类签名需要拿到请求体原文计算 payload 摘要，而 {@link #applyAuth} 只能拿到
	 * 请求 builder（body 已编码为 BodyPublisher，无法取回原文）。</p>
	 *
	 * <p>本方法在 {@link RequestBuilder} 构建请求时调用，位于 {@link #applyAuth} 与
	 * {@code extraHeaders} 之后；返回的头会逐对 {@code header} 到 builder 上（自动跳过
	 * {@code Host}，因 JDK HttpClient 禁止显式设置 Host）。</p>
	 *
	 * <p>每次重试都会重新调用本方法（请求 supplier 每次 attempt 重建请求），因此子类无需缓存
	 * 签名结果——时间戳类签名（如 SigV4 的 x-amz-date）会随重试刷新，这正是期望行为。</p>
	 *
	 * @param method HTTP 方法（GET/POST 等）
	 * @param url    完整 URL
	 * @param body   请求体字符串（GET 请求为空字符串；multipart 为已编码表单文本）
	 * @return 签名后的请求头（key→value），可为空 map 但不为 null
	 */
	protected Map<String, String> signRequest(String method, String url, String body) {
		return Map.of();
	}

	/**
	 * 平台标识名（用于能力快速失败异常信息）。
	 *
	 * <p>默认返回类简单名；实现 {@link AiClient} 的子类已覆写为平台 slug（如 {@code "deepseek"}）。
	 * 这里提供一个具体默认实现，使 {@link #guard(Capability)} 不依赖 {@code AiClient} 接口即可工作。</p>
	 *
	 * @return 平台标识名
	 * @since 1.4.0
	 */
	protected String name() {
		return getClass().getSimpleName();
	}

	/**
	 * 声明本 Client 实际支持的能力集合（1.4.0 可维护性迭代 P2-6）。
	 *
	 * <p>默认空集合；{@link com.sure.ai.client.compat.OpenAiCompatClient} 覆写为其引擎实现的全量能力，作为未逐平台审计
	 * 子类的安全默认（不触发 guard、行为与重构前一致）。已逐平台审计的子类（DeepSeek、Mistral、
	 * Grok、LlamaCpp、Moonshot 等）覆写为自身真实支持的子集，使不支持的能力在发请求前快速失败。</p>
	 *
	 * <p>实现应为不可变集合且每次调用返回等价内容；{@link #guard(Capability)} 仅做只读 contains 判断。</p>
	 *
	 * @return 支持的能力集合，不为 null
	 * @since 1.4.0
	 */
	protected Set<Capability> capabilities() {
		return Set.of();
	}

	/**
	 * 能力守卫：若本 Client 未声明支持 {@code c}，在发请求前抛出清晰的
	 * {@link com.sure.ai.exception.AiException}。
	 *
	 * <p>终态方法，子类不可覆写以保证语义统一。对已声明支持的能力是零开销空操作。</p>
	 *
	 * @param c 待检查的能力
	 * @throws com.sure.ai.exception.AiException 未声明支持该能力时
	 * @since 1.4.0
	 */
	protected final void guard(Capability c) {
		if (!capabilities().contains(c)) {
			throw new AiException(name() + " does not support " + c + " capability");
		}
	}

	/**
	 * JSON POST 并返回解析结果与原始报文。
	 *
	 * @param path 接口路径
	 * @param body 请求体
	 * @return 解析结果（含原始报文）
	 */
	protected PostResult doPostRaw(String path, JsonObject body) {
		String url = resolveUrl(path);
		String payload = Json.stringify(body);
		return retryExecutor.execute(path,
			() -> requestBuilder.post(url, payload).build(),
			BodyHandlers.ofString(StandardCharsets.UTF_8),
			b -> new PostResult(parseJson(b), b),
			(status, b) -> b);
	}

	/**
	 * JSON POST 并返回响应对象。
	 *
	 * <p>2xx 返回解析后的 JsonObject；非 2xx 经 {@link #mapError} 抛出；429/5xx 按 Retry-After
	 * 或指数退避重试 maxRetries 次。</p>
	 *
	 * @param path 接口路径
	 * @param body 请求体
	 * @return 响应 JSON
	 */
	protected JsonObject doPost(String path, JsonObject body) {
		return doPostRaw(path, body).json();
	}

	/**
	 * SSE 流式 POST。
	 *
	 * @param path          接口路径
	 * @param body          请求体
	 * @param chunkConsumer 每个 data 行解析出的 JSON 元素消费者
	 */
	protected void doPostStream(String path, JsonObject body,
			Consumer<JsonElement> chunkConsumer) {
		String url = resolveUrl(path);
		String payload = Json.stringify(body);
		retryExecutor.execute(path,
			() -> requestBuilder.post(url, payload).build(),
			BodyHandlers.ofInputStream(),
			(InputStream in) -> {
				SseLineReader.read(in, StandardCharsets.UTF_8, ev -> {
					if ("[DONE]".equals(ev.data())) {
						return;
					}
					chunkConsumer.accept(Json.parse(ev.data()));
				});
				return null;
			},
			(status, in) -> readAll(in));
	}

	/**
	 * JSON GET 并返回响应对象（用于异步任务轮询等场景）。
	 *
	 * <p>2xx 返回解析后的 JsonObject；非 2xx 经 {@link #mapError} 抛出；429/5xx 按 Retry-After
	 * 或指数退避重试 maxRetries 次。</p>
	 *
	 * @param path 接口路径
	 * @return 响应 JSON
	 */
	protected JsonObject doGet(String path) {
		return doGetRaw(path).json();
	}

	/**
	 * JSON GET 并返回解析结果与原始报文。
	 *
	 * @param path 接口路径
	 * @return 解析结果（含原始报文）
	 */
	protected PostResult doGetRaw(String path) {
		String url = resolveUrl(path);
		return retryExecutor.execute(path,
			() -> requestBuilder.get(url).build(),
			BodyHandlers.ofString(StandardCharsets.UTF_8),
			b -> new PostResult(parseJson(b), b),
			(status, b) -> b);
	}

	/**
	 * JSON POST 并返回二进制响应体（用于 TTS 等返回音频二进制的接口）。
	 *
	 * @param path 接口路径
	 * @param body 请求体 JSON
	 * @return 二进制响应体
	 */
	protected byte[] doPostBinary(String path, JsonObject body) {
		String url = resolveUrl(path);
		String payload = Json.stringify(body);
		return retryExecutor.execute(path,
			() -> requestBuilder.post(url, payload).build(),
			BodyHandlers.ofByteArray(),
			b -> b,
			(status, b) -> new String(b, StandardCharsets.UTF_8));
	}

	/**
	 * multipart/form-data POST（用于 STT 音频文件上传等接口），返回解析结果与原始报文。
	 *
	 * @param path            接口路径
	 * @param textFields      文本字段（name → value）
	 * @param fileField       文件字段名
	 * @param fileName        文件名
	 * @param fileContentType 文件 MIME 类型
	 * @param fileData        文件二进制数据
	 * @return 解析结果（含原始报文）
	 */
	protected PostResult doPostMultipart(String path, Map<String, String> textFields,
			String fileField, String fileName, String fileContentType, byte[] fileData) {
		String url = resolveUrl(path);
		String boundary = "----sureai" + UUID.randomUUID().toString().replace("-", "");
		byte[] body = MultipartBodyBuilder.build(boundary, textFields, fileField, fileName,
			fileContentType, fileData);
		return retryExecutor.execute(path,
			() -> requestBuilder.multipart(url, boundary, body).build(),
			BodyHandlers.ofString(StandardCharsets.UTF_8),
			b -> new PostResult(parseJson(b), b),
			(status, b) -> b);
	}

	/**
	 * 子类解析到 Token 用量时调用，转发给已挂载的 MetricsCollector（未挂载零开销）。
	 *
	 * @param model            模型名
	 * @param promptTokens     提示 token 数
	 * @param completionTokens 补全 token 数
	 * @param totalTokens      总 token 数
	 */
	protected void notifyTokenUsage(String model, long promptTokens, long completionTokens,
			long totalTokens) {
		MetricsCollector mc = this.config.metricsCollector();
		if (mc == null) {
			return;
		}
		RetryExecutor.safeMetrics(
			() -> mc.onTokenUsage(model, promptTokens, completionTokens, totalTokens));
	}

	/** 读取错误响应体。 */
	private static String readAll(InputStream in) {
		try (InputStream s = in) {
			return new String(s.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			return "";
		}
	}

	/** 拼接完整 URL。 */
	private String resolveUrl(String path) {
		String base = this.config.baseUrl();
		if (base == null || base.isBlank()) {
			throw new AiException("baseUrl is not configured");
		}
		if (base.endsWith("/") && path.startsWith("/")) {
			return base + path.substring(1);
		}
		if (!base.endsWith("/") && !path.startsWith("/")) {
			return base + "/" + path;
		}
		return base + path;
	}

	/**
	 * 对 URL 路径段（path segment）做百分号编码。
	 *
	 * <p>与查询串编码不同：路径段中的空格应编码为 {@code %20} 而非 {@code +}，
	 * 故在 {@link URLEncoder} 基础上将 {@code +} 替换为 {@code %20}、
	 * 将 {@code %2F} 保留（不允许路径段内出现 {@code /}，避免穿越）。</p>
	 *
	 * @param segment 原始路径段（如 taskId / jobId / batchId）
	 * @return 编码后的路径段
	 */
	protected static String encodePathSegment(String segment) {
		if (segment == null || segment.isEmpty()) {
			return segment;
		}
		return URLEncoder.encode(segment, StandardCharsets.UTF_8)
			.replace("+", "%20")
			.replace("%7E", "~");
	}

	/**
	 * 将 HTTP 错误映射为异常（默认实现，子类可覆盖）。
	 *
	 * @param httpStatus HTTP 状态码
	 * @param rawBody    原始响应体
	 * @return 异常
	 */
	protected AiException mapError(int httpStatus, String rawBody) {
		return RetryExecutor.mapError(httpStatus, rawBody, null);
	}

	/**
	 * 序列化为 JSON 文本。
	 *
	 * @param obj 对象
	 * @return JSON 文本
	 */
	protected String toJson(Object obj) {
		return Json.stringify(obj);
	}

	/**
	 * 解析 JSON 文本为对象。
	 *
	 * @param json JSON 文本
	 * @return 对象
	 */
	protected JsonObject parseJson(String json) {
		return Json.parse(json).getAsJsonObject();
	}

	/**
	 * POST 结果：解析后的 JSON 与原始报文。
	 *
	 * @param json     解析后的对象
	 * @param rawBody  原始响应体
	 */
	protected record PostResult(JsonObject json, String rawBody) {
	}

	/**
	 * 释放资源（HttpClient 在 JDK21 可关闭，此处保持空实现以兼容接口约定）。
	 */
	public void close() {
		// 留空：JDK HttpClient 无需显式释放
	}
}
