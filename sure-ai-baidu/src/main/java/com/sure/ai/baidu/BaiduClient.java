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

package com.sure.ai.baidu;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AudioClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.FineTuneClient;
import com.sure.ai.exception.AiApiException;
import com.sure.ai.exception.AiAuthException;
import com.sure.ai.exception.AiException;
import com.sure.ai.exception.AiTimeoutException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.Choice;
import com.sure.ai.model.DocumentPart;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;
import com.sure.ai.model.ImagePart;
import com.sure.ai.model.MessagePart;
import com.sure.ai.model.Role;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TextPart;
import com.sure.ai.model.TokenUsage;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;

/**
 * 百度智能云千帆（文心 ERNIE）客户端。
 *
 * <p>自研实现，不复用 OpenAI 兼容引擎。差异点：</p>
 * <ul>
 *   <li>两步鉴权：先用 API Key + Secret Key 以 {@code application/x-www-form-urlencoded}
 *       表单 POST 调 OAuth 换 access_token（凭证不进 URL 查询串），后续业务接口通过
 *       {@code Authorization: Bearer <token>} 请求头传递 token；token 按
 *       {@code expires_in} 缓存（提前 60 秒刷新），并发下只刷新一次。</li>
 *   <li>对话模型是路径参数：{@code /rpc/2.0/ai_custom/v1/wenxinworkshop/chat/{model}}，请求体无 model 字段。</li>
 *   <li>非流式响应正文在 {@code result} 字段，流式 SSE 每个分片的增量在 {@code result}，结束标志 {@code is_end=true}。</li>
 *   <li>错误体为 {@code {error_code, error_msg}}，映射到 {@link AiApiException#getErrorCode()}。</li>
 * </ul>
 *
 * <p>默认 baseUrl：{@code https://aip.baidubce.com}。</p>
 *
 * <p>P1 能力：</p>
 * <ul>
 *   <li>多模态：{@link ImagePart} 序列化为 {@code image_url}（data URL 形式，与 OpenAI
 *       兼容）；{@link DocumentPart} 百度千帆对话接口不支持 PDF 输入，直接抛
 *       {@link AiException}；</li>
 *   <li>结构化输出：百度的 {@code response_format} 是<b>字符串取值</b>
 *       （{@code "json_object"}/{@code "text"}），而非对象；传入 String 时直接透传，
 *       传入含 {@code type} 的对象时提取该字符串。</li>
 * </ul>
 *
 * <p>TTS / STT 使用百度语音开放平台（{@code tsn.baidu.com} / {@code vop.baidu.com}），与对话服务
 * 不同域，端点默认硬编码，可用 {@code extraHeaders("ttsUrl", ...)} / {@code extraHeaders("sttUrl", ...)}
 * 覆盖（测试 mock 时使用）。百度暂无公开的视频生成 API，本模块不提供视频生成能力。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class BaiduClient extends AbstractAiClient implements AiClient, EmbeddingClient, AudioClient, FineTuneClient {

	/** 千帆微调接口相对路径（拼到 baseUrl 后）。 */
	public static final String FINETUNE_BASE = "/rpc/2.0/ai_custom/v1/wenxinworkshop/finetune";

	/** 千帆文件上传接口相对路径。 */
	public static final String FILE_UPLOAD_PATH = "/rpc/2.0/ai_custom/v1/wenxinworkshop/files/upload";

	/** 默认 baseUrl。 */
	public static final String DEFAULT_BASE_URL = "https://aip.baidubce.com";

	/** TTS 默认端点（短文本语音合成）。 */
	public static final String DEFAULT_TTS_URL = "https://tsn.baidu.com/text2audio";

	/** STT 默认端点（短语音识别）。 */
	public static final String DEFAULT_STT_URL = "https://vop.baidu.com/server_api";

	/** ttsUrl 在 extraHeaders 中的键名。 */
	public static final String TTS_URL_HEADER = "ttsUrl";

	/** sttUrl 在 extraHeaders 中的键名。 */
	public static final String STT_URL_HEADER = "sttUrl";

	/** OAuth access_token 提前刷新的安全余量（毫秒）。 */
	private static final long TOKEN_LEEWAY_MS = 60_000L;

	/** secretKey 在 extraHeaders 中的键名。 */
	public static final String SECRET_KEY_HEADER = "secretKey";

	/** cuid 用户标识。 */
	private static final String CUID = "sureai";

	/** secretKey。 */
	private final String secretKey;

	/** TTS 端点。 */
	private final String ttsUrl;

	/** STT 端点。 */
	private final String sttUrl;

	/** 已缓存的 access_token。 */
	private volatile String cachedToken;

	/** token 过期时间戳（毫秒）。 */
	private volatile long tokenExpireAt;

	/** token 刷新锁。 */
	private final Object tokenLock = new Object();

	/**
	 * 构造客户端。
	 *
	 * @param config 配置：apiKey 为千帆 API Key，secretKey 需放在 extraHeaders("secretKey", ...)
	 */
	public BaiduClient(AiConfig config) {
		super(withDefaults(config));
		this.secretKey = config.extraHeaders().get(SECRET_KEY_HEADER);
		this.ttsUrl = config.extraHeaders().getOrDefault(TTS_URL_HEADER, DEFAULT_TTS_URL);
		this.sttUrl = config.extraHeaders().getOrDefault(STT_URL_HEADER, DEFAULT_STT_URL);
	}

	@Override
	public String name() {
		return "baidu";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		// 业务接口统一通过 Authorization: Bearer 头传递 access_token，不再拼在 URL 查询串
		requestBuilder.header("Authorization", "Bearer " + getAccessToken());
	}

	@Override
	public ChatResponse chat(ChatRequest request) {
		String path = "/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/" + request.model();
		JsonObject body = buildChatBody(request, false);
		try {
			PostResult result = doPostRaw(path, body);
			return parseChatResponse(result.json(), result.rawBody(), request.model());
		} catch (AiApiException ex) {
			throw (AiApiException) enrichError(ex);
		}
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		String path = "/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/" + request.model();
		JsonObject body = buildChatBody(request, true);
		try {
			doPostStream(path, body, el -> consumer.accept(parseStreamChunk(el)));
		} catch (AiApiException ex) {
			throw (AiApiException) enrichError(ex);
		}
	}

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		String path = "/rpc/2.0/ai_custom/v1/wenxinworkshop/embeddings/" + request.model();
		JsonObject body = Json.object();
		JsonArray input = Json.array();
		for (String s : request.input()) {
			input.add(s);
		}
		body.put("input", input);
		try {
			JsonObject resp = doPost(path, body);
			return parseEmbeddingResponse(resp);
		} catch (AiApiException ex) {
			throw (AiApiException) enrichError(ex);
		}
	}

	// ==================== 语音合成 TTS（短文本，form-urlencoded） ====================

	@Override
	public TtsResponse synthesize(TtsRequest request) {
		String token = getAccessToken();
		int aue = mapAue(request.responseFormat());
		Map<String, String> form = new LinkedHashMap<>();
		form.put("tex", request.input());
		form.put("tok", token);
		form.put("cuid", CUID);
		form.put("ctp", "1");
		form.put("lan", "zh");
		form.put("spd", String.valueOf(mapSpeed(request.speed())));
		form.put("pit", "5");
		form.put("vol", "5");
		form.put("per", request.voice() == null ? BaiduModels.TTS_PER_XIAOMEI : request.voice());
		form.put("aue", String.valueOf(aue));
		HttpResponse<byte[]> resp = postForm(this.ttsUrl, form);
		String contentType = resp.headers().firstValue("Content-Type").orElse("");
		if (contentType.startsWith("audio/")) {
			return TtsResponse.ofAudio(resp.body(), formatFromAue(aue));
		}
		String raw = new String(resp.body(), StandardCharsets.UTF_8);
		throw parseTtsError(raw);
	}

	/**
	 * 发送 x-www-form-urlencoded POST，返回字节响应（非 2xx 经 mapError 抛出）。
	 *
	 * <p><b>注意：此路径直接使用 HttpClient，不经过基类的重试/熔断/指标机制。</b>
	 * （TTS 为 form-urlencoded + 二进制音频响应，不适用基类 JSON 封装。）</p>
	 */
	private HttpResponse<byte[]> postForm(String url, Map<String, String> form) {
		String body = encodeForm(form);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "audio/*")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		try {
			HttpResponse<byte[]> resp = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
			int status = resp.statusCode();
			if (status >= 200 && status < 300) {
				return resp;
			}
			throw mapError(status, new String(resp.body(), StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiTimeoutException("baidu tts request failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("baidu tts interrupted", ex);
		}
	}

	/** 表单编码：UTF-8 URL 编码，& 连接。 */
	private static String encodeForm(Map<String, String> form) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : form.entrySet()) {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
			sb.append('=');
			sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	/** TTS 错误响应 {err_no, err_msg} 映射为 AiApiException。 */
	private static AiApiException parseTtsError(String raw) {
		try {
			JsonObject o = Json.parse(raw).getAsJsonObject();
			int errNo = o.optInt("err_no", -1);
			String msg = o.optString("err_msg", raw);
			return new AiApiException(200, String.valueOf(errNo), "baidu tts failed: " + msg, raw);
		} catch (RuntimeException ex) {
			return new AiApiException(200, null, "baidu tts failed: " + raw, raw);
		}
	}

	/** responseFormat 映射到 aue：mp3=3 / pcm=4 / wav=6，缺省 mp3。 */
	private static int mapAue(String format) {
		if (format == null || format.isBlank()) {
			return 3;
		}
		switch (format.trim().toLowerCase()) {
			case "wav":
			case "wave":
				return 6;
			case "pcm":
				return 4;
			default:
				return 3;
		}
	}

	/** aue 反查格式字符串。 */
	private static String formatFromAue(int aue) {
		return switch (aue) {
			case 4 -> "pcm";
			case 6 -> "wav";
			default -> "mp3";
		};
	}

	/** 语速 0.25–4.0 映射到百度 spd 0–15，缺省 5。 */
	private static int mapSpeed(Double speed) {
		if (speed == null) {
			return 5;
		}
		int v = (int) Math.round(speed * 5.0);
		return Math.max(0, Math.min(15, v));
	}

	// ==================== 语音识别 STT（JSON base64） ====================

	@Override
	public SttResponse transcribe(SttRequest request) {
		String token = getAccessToken();
		String b64 = Base64.getEncoder().encodeToString(request.audioData());
		JsonObject body = Json.object();
		body.put("format", "pcm");
		body.put("rate", 16000);
		body.put("channel", 1);
		body.put("cuid", CUID);
		body.put("token", token);
		body.put("dev_pid", resolveDevPid(request));
		body.put("speech", b64);
		body.put("len", request.audioData().length);
		JsonObject resp = postJson(this.sttUrl, body);
		int errNo = resp.optInt("err_no", 0);
		if (errNo != 0) {
			throw new AiApiException(200, String.valueOf(errNo),
				"baidu stt failed: " + resp.optString("err_msg", ""), resp.toString());
		}
		JsonArray resultArr = resp.has("result") ? resp.getJsonArray("result") : null;
		String text = (resultArr == null || resultArr.isEmpty()) ? "" : resultArr.getString(0);
		return SttResponse.ofText(text);
	}

	/**
	 * 向绝对 URL 发送 JSON POST，返回解析后的对象（非 2xx 经 mapError 抛出）。
	 *
	 * <p><b>注意：此路径直接使用 HttpClient，不经过基类的重试/熔断/指标机制。</b>
	 * （STT 等接口使用 access-token 鉴权与独立端点，不适用基类 doPost。）</p>
	 */
	private JsonObject postJson(String url, JsonObject body) {
		String payload = Json.stringify(body);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
			.build();
		try {
			HttpResponse<String> resp = this.httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
			int status = resp.statusCode();
			if (status >= 200 && status < 300) {
				return parseJson(resp.body());
			}
			throw mapError(status, resp.body());
		} catch (IOException ex) {
			throw new AiTimeoutException("baidu stt request failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("baidu stt interrupted", ex);
		}
	}

	/** 解析 dev_pid：优先 SttRequest.extra("dev_pid")，缺省普通话 1537。 */
	private static int resolveDevPid(SttRequest request) {
		Object v = request.extra().get("dev_pid");
		if (v instanceof Number n) {
			return n.intValue();
		}
		if (v instanceof String s && !s.isBlank()) {
			try {
				return Integer.parseInt(s.trim());
			} catch (NumberFormatException ignored) {
				// 解析失败回退默认
			}
		}
		return BaiduModels.ASR_DEV_PID_MANDARIN;
	}

	/** 解析非流式对话响应：result 字段为正文。 */
	private ChatResponse parseChatResponse(JsonObject resp, String raw, String model) {
		String id = resp.optString("id", null);
		String result = resp.optString("result", null);
		ChatMessage message = ChatMessage.of(Role.ASSISTANT, result, null, null, null, null);
		List<Choice> choices = List.of(Choice.of(0, message, "stop"));
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
				u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
		}
		return ChatResponse.of(id, model, choices, usage, raw);
	}

	/** 解析流式分片：result 为增量，is_end=true 时结束。 */
	private ChatStreamChunk parseStreamChunk(JsonElement el) {
		JsonObject o = el.getAsJsonObject();
		String delta = o.optString("result", null);
		boolean isEnd = o.has("is_end") && o.getBoolean("is_end");
		String finish = isEnd ? "stop" : null;
		return ChatStreamChunk.of(o.optString("id", null), null, delta, null, finish);
	}

	/** 解析向量响应。 */
	private EmbeddingResponse parseEmbeddingResponse(JsonObject resp) {
		String model = resp.optString("model", null);
		JsonArray data = resp.getJsonArray("data");
		List<float[]> embeddings = new ArrayList<>();
		for (int i = 0; i < data.size(); i++) {
			JsonObject d = data.getJsonObject(i);
			JsonArray emb = d.getJsonArray("embedding");
			float[] vec = new float[emb.size()];
			for (int j = 0; j < emb.size(); j++) {
				vec[j] = (float) emb.getDouble(j);
			}
			embeddings.add(vec);
		}
		TokenUsage usage = null;
		if (resp.has("usage")) {
			JsonObject u = resp.getJsonObject("usage");
			usage = TokenUsage.of(u.optInt("prompt_tokens", 0),
				u.optInt("completion_tokens", 0), u.optInt("total_tokens", 0));
		}
		return EmbeddingResponse.of(model, embeddings, usage);
	}

	/** 构造对话请求体（无 model 字段，model 在 URL 路径）。 */
	private JsonObject buildChatBody(ChatRequest req, boolean stream) {
		JsonObject body = Json.object();
		JsonArray messages = Json.array();
		for (ChatMessage m : req.messages()) {
			JsonObject o = Json.object();
			o.put("role", m.role().value());
			if (m.parts() != null && !m.parts().isEmpty()) {
				JsonArray content = Json.array();
				for (MessagePart part : m.parts()) {
					content.add(serializePart(part));
				}
				o.set("content", content);
			} else {
				o.put("content", m.content() == null ? "" : m.content());
			}
			messages.add(o);
		}
		body.put("messages", messages);
		body.put("stream", stream);
		if (req.temperature() != null) {
			body.put("temperature", req.temperature());
		}
		if (req.topP() != null) {
			body.put("top_p", req.topP());
		}
		if (req.maxTokens() != null) {
			body.put("max_output_tokens", req.maxTokens());
		}
		applyResponseFormat(body, req.responseFormat());
		return body;
	}

	/**
	 * 序列化多模态片段：text / image_url(data URL)。
	 *
	 * @param part 消息片段
	 * @return 内容块
	 * @throws AiException 遇到 DocumentPart 时抛出（百度不支持 PDF）
	 */
	private static JsonObject serializePart(MessagePart part) {
		JsonObject o = Json.object();
		if (part instanceof TextPart tp) {
			o.put("type", "text");
			o.put("text", tp.text());
		} else if (part instanceof ImagePart ip) {
			o.put("type", "image_url");
			JsonObject imageUrl = Json.object();
			imageUrl.put("url", ip.resolvedUrl());
			o.set("image_url", imageUrl);
		} else if (part instanceof DocumentPart) {
			throw new AiException("Baidu does not support document/PDF input");
		}
		return o;
	}

	/**
	 * 写入 response_format（百度为字符串取值）。
	 *
	 * <p>String 直接透传；含 {@code type} 的对象提取其字符串；其余忽略。</p>
	 *
	 * @param body           请求体
	 * @param responseFormat 响应格式
	 */
	private static void applyResponseFormat(JsonObject body, Object responseFormat) {
		if (responseFormat == null) {
			return;
		}
		String value = null;
		if (responseFormat instanceof String s) {
			value = s;
		} else {
			JsonElement el = Json.toElement(responseFormat);
			if (el.isObject()) {
				value = el.getAsJsonObject().optString("type", null);
			}
		}
		if (value != null) {
			body.put("response_format", value);
		}
	}

	/**
	 * 获取有效 access_token：缓存未过期复用，否则双检锁重新换取。
	 *
	 * <p><b>注意：此路径直接使用 HttpClient，不经过基类的重试/熔断/指标机制。</b>
	 * （OAuth token 端点非业务接口，按 Baidu OAuth 协议单独实现。）</p>
	 */
	private String getAccessToken() {
		String token = this.cachedToken;
		if (token != null && System.currentTimeMillis() < this.tokenExpireAt - TOKEN_LEEWAY_MS) {
			return token;
		}
		synchronized (this.tokenLock) {
			if (this.cachedToken == null
				|| System.currentTimeMillis() >= this.tokenExpireAt - TOKEN_LEEWAY_MS) {
				fetchToken();
			}
			return this.cachedToken;
		}
	}

	/** 调 OAuth 接口换取 access_token 并更新缓存。 */
	private void fetchToken() {
		if (this.secretKey == null || this.secretKey.isBlank()) {
			throw new AiException("baidu secretKey is not configured (extraHeaders secretKey)");
		}
		// 凭证走 POST body（application/x-www-form-urlencoded），不拼在 URL 查询串，
		// 避免 client_secret 进入代理访问日志与服务器 access log。
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "client_credentials");
		form.put("client_id", this.config.apiKey());
		form.put("client_secret", this.secretKey);
		String formBody = encodeForm(form);
		HttpRequest request = HttpRequest.newBuilder(URI.create(this.config.baseUrl() + "/oauth/2.0/token"))
			.timeout(this.config.timeout())
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> resp;
		try {
			resp = this.httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			throw new AiTimeoutException("fetch baidu token failed: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException("fetch baidu token interrupted", ex);
		}
		int status = resp.statusCode();
		if (status < 200 || status >= 300) {
			throw enrichError(new AiAuthException("baidu token http " + status, resp.body()));
		}
		JsonObject o = parseJson(resp.body());
		if (!o.has("access_token")) {
			throw new AiAuthException("baidu token response missing access_token", resp.body());
		}
		this.cachedToken = o.getString("access_token");
		long expiresInSec = o.get("expires_in").getAsLong();
		this.tokenExpireAt = System.currentTimeMillis() + expiresInSec * 1000L;
	}

	/** 从错误体解析 error_code / error_msg，保留异常类型。 */
	private AiApiException enrichError(AiApiException ex) {
		String raw = ex.getRawBody();
		if (raw == null || raw.isBlank()) {
			return ex;
		}
		try {
			JsonObject o = parseJson(raw);
			if (o.has("error_code")) {
				String code = String.valueOf(o.getInt("error_code"));
				String msg = o.optString("error_msg", ex.getMessage());
				if (ex instanceof AiAuthException) {
					return new AiAuthException(ex.getHttpStatus(), msg, raw);
				}
				return new AiApiException(ex.getHttpStatus(), code, msg, raw);
			}
		} catch (RuntimeException ignored) {
			// 错误体不是 JSON，原样抛出
		}
		return ex;
	}

	// ==================== 微调（Fine-tuning） ====================

	/**
	 * 创建 SFT 微调任务（基础框架）。
	 *
	 * <p>调用千帆 {@code /finetune/create} 端点，使用 access_token 鉴权。
	 * 请求体映射：{@code baseModel=model}、{@code trainDataset=trainingFileId}、
	 * {@code modelName=suffix}，超参数透传到 {@code hyperParameters}。</p>
	 *
	 * <p><b>限制：</b>千帆微调任务的完整超参数 schema（多任务类型、评测集、模型续训等）
	 * 随官方文档演进，本方法实现常用 SFT 子集；若官方协议更新，请通过
	 * {@link FineTuneRequest.Builder#extra(String, Object)} 追加字段或联系维护方扩展。</p>
	 *
	 * @param request 微调请求
	 * @return 任务响应
	 */
	@Override
	public FineTuneResponse createFineTune(FineTuneRequest request) {
		JsonObject body = Json.object();
		body.put("baseModel", request.model());
		body.put("trainType", "sft");
		body.put("trainDataset", request.trainingFileId());
		if (request.suffix() != null) {
			body.put("modelName", request.suffix());
		}
		if (request.hyperparameters() != null) {
			body.set("hyperParameters", Json.toElement(request.hyperparameters()));
		}
		PostResult pr = doPostRaw(FINETUNE_BASE + "/create", body);
		return parseFineTune(pr.rawBody(), request.model());
	}

	/**
	 * 查询微调任务状态。
	 *
	 * @param jobId 任务 ID（千帆 taskId）
	 * @return 任务响应
	 */
	@Override
	public FineTuneResponse getFineTune(String jobId) {
		JsonObject body = Json.object();
		body.put("taskId", jobId);
		PostResult pr = doPostRaw(FINETUNE_BASE + "/get", body);
		return parseFineTune(pr.rawBody(), null);
	}

	/**
	 * 上传训练数据文件，返回 file_id（基础框架）。
	 *
	 * <p><b>限制：</b>千帆文件上传为 multipart/form-data 协议，且需先在控制台开通
	 * 数据集；本方法以 JSON 形式提交文件名与 base64 内容作为框架实现，
	 * 生产环境如需严格对齐 multipart 协议请联系维护方扩展。</p>
	 *
	 * @param fileName 文件名
	 * @param content  文件二进制内容（JSONL）
	 * @return file_id
	 */
	@Override
	public String uploadTrainingFile(String fileName, byte[] content) {
		JsonObject body = Json.object();
		body.put("fileName", fileName);
		body.put("fileType", "jsonl");
		body.put("content", Base64.getEncoder().encodeToString(content));
		PostResult pr = doPostRaw(FILE_UPLOAD_PATH, body);
		JsonObject resp = pr.json();
		String id = resp.optString("fileId", resp.optString("id", null));
		if (id == null) {
			throw new AiException("baidu uploadTrainingFile: no fileId in response: " + pr.rawBody());
		}
		return id;
	}

	/** 解析千帆微调任务响应，把百度状态映射为规范状态。 */
	private static FineTuneResponse parseFineTune(String rawJson, String defaultModel) {
		JsonObject o = Json.parse(rawJson).getAsJsonObject();
		String id = o.optString("taskId", o.optString("id", null));
		String rawStatus = o.optString("status", "");
		String status = mapStatus(rawStatus);
		String fineTuned = o.optString("fineTunedModel",
			o.optString("outputModel", o.optString("modelName", null)));
		String model = o.optString("baseModel", o.optString("model", defaultModel));
		String error = o.optString("errorMsg", o.optString("message", null));
		return FineTuneResponse.of(id, status, model, fineTuned, null, null, error, rawJson);
	}

	/** 千帆状态 → 规范状态。 */
	private static String mapStatus(String raw) {
		switch (raw) {
			case "Running":
			case "RUNNING":
			case "running":
				return "running";
			case "Pending":
			case "WAITING":
			case "Queued":
				return "queued";
			case "Done":
			case "Success":
			case "SUCCEEDED":
				return "succeeded";
			case "Failed":
			case "FAILURE":
				return "failed";
			case "Cancelled":
			case "Canceled":
				return "cancelled";
			default:
				return raw.isBlank() ? "unknown" : raw;
		}
	}

	/** baseUrl 为空时补默认地址，其余配置通过 {@link AiConfig#withBaseUrl} 原样保留。 */
	private static AiConfig withDefaults(AiConfig config) {
		if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
			return config;
		}
		return config.withBaseUrl(DEFAULT_BASE_URL);
	}
}
