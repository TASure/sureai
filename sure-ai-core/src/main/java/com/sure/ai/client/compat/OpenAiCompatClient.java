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

package com.sure.ai.client.compat;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sure.ai.client.AbstractAiClient;
import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.client.AudioClient;
import com.sure.ai.client.EmbeddingClient;
import com.sure.ai.client.FineTuneClient;
import com.sure.ai.client.ImageClient;
import com.sure.ai.client.ModelsClient;
import com.sure.ai.client.ModerationClient;
import com.sure.ai.client.VideoClient;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.EmbeddingRequest;
import com.sure.ai.model.EmbeddingResponse;
import com.sure.ai.model.FineTuneRequest;
import com.sure.ai.model.FineTuneResponse;
import com.sure.ai.model.ImageRequest;
import com.sure.ai.model.ImageResponse;
import com.sure.ai.model.Model;
import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;
import com.sure.ai.model.SttRequest;
import com.sure.ai.model.SttResponse;
import com.sure.ai.model.TtsRequest;
import com.sure.ai.model.TtsResponse;
import com.sure.ai.model.VideoRequest;
import com.sure.ai.model.VideoResponse;

/**
 * OpenAI 兼容协议客户端引擎（薄编排层）。
 *
 * <p>1.4.0 可维护性迭代：本类由 841 行的"上帝类"按能力域拆分为一组包内可见协作策略——
 * {@link ChatCompatStrategy}（非流式 chat + 请求体序列化 + 响应解析）、
 * {@link StreamCompatStrategy}（SSE 流式分片解析）、{@link EmbeddingCompatStrategy}、
 * {@link ImageCompatStrategy}、{@link VideoCompatStrategy}（异步轮询）、
 * {@link AudioCompatStrategy}（TTS/STT）、{@link ModerationCompatStrategy}、
 * {@link FineTuneCompatStrategy}（含训练文件上传）。</p>
 *
 * <p>本类收敛为：持有各策略引用、暴露平台子类覆写所需的 protected 契约（路径字段、轮询参数、
 * {@link #buildChatBody}、{@link #parseFineTuneResponse}、{@link #applyAuth}），并把
 * 全部 public 方法委托给对应策略。公共 API 与子类契约不变，行为与拆分前完全一致。</p>
 *
 * <p>策略类与本类同处 {@code com.sure.ai.client.compat} 包；由于 {@code doPostRaw} 等
 * 传输方法声明在 {@code com.sure.ai.client} 包的 {@link AbstractAiClient} 中（protected），
 * 跨包策略无法直接调用，故本类暴露一组包内可见的 {@code transport*} 桥接方法做转发。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public class OpenAiCompatClient extends AbstractAiClient
		implements AiClient, EmbeddingClient, ImageClient, VideoClient, AudioClient,
		ModelsClient, ModerationClient, FineTuneClient {

	/** 对话接口路径，子类可覆盖。 */
	protected String chatPath = "/chat/completions";

	/** 向量接口路径，子类可覆盖。 */
	protected String embeddingsPath = "/embeddings";

	/** 图像生成接口路径，子类可覆盖。 */
	protected String imagesPath = "/images/generations";

	/** 视频生成接口路径（提交任务），子类可覆盖。 */
	protected String videosPath = "/videos";

	/** 语音合成接口路径，子类可覆盖。 */
	protected String ttsPath = "/audio/speech";

	/** 语音识别接口路径，子类可覆盖。 */
	protected String sttPath = "/audio/transcriptions";

	/** 模型列表接口路径，子类可覆盖。 */
	protected String modelsPath = "/models";

	/** 内容审核接口路径，子类可覆盖。 */
	protected String moderationsPath = "/moderations";

	/** 微调任务接口路径，子类可覆盖。 */
	protected String fineTunePath = "/fine_tuning/jobs";

	/** 文件上传接口路径，子类可覆盖。 */
	protected String filesPath = "/files";

	/** 视频轮询间隔（毫秒），子类可覆盖。 */
	protected long videoPollIntervalMs = 2000L;

	/** 视频最大等待时间（毫秒），子类可覆盖。 */
	protected long videoMaxWaitMs = 120000L;

	/** 非流式 chat 策略。 */
	private final ChatCompatStrategy chatStrategy;

	/** 流式 chat 策略。 */
	private final StreamCompatStrategy streamStrategy;

	/** 向量策略。 */
	private final EmbeddingCompatStrategy embeddingStrategy;

	/** 图像策略。 */
	private final ImageCompatStrategy imageStrategy;

	/** 视频策略。 */
	private final VideoCompatStrategy videoStrategy;

	/** 音频（TTS/STT）策略。 */
	private final AudioCompatStrategy audioStrategy;

	/** 内容审核策略。 */
	private final ModerationCompatStrategy moderationStrategy;

	/** 微调策略。 */
	private final FineTuneCompatStrategy fineTuneStrategy;

	/**
	 * 构造客户端。
	 *
	 * @param config 配置（baseUrl 由平台子类或调用方设置）
	 */
	public OpenAiCompatClient(AiConfig config) {
		super(config);
		this.chatStrategy = new ChatCompatStrategy(this);
		this.streamStrategy = new StreamCompatStrategy(this);
		this.embeddingStrategy = new EmbeddingCompatStrategy(this);
		this.imageStrategy = new ImageCompatStrategy(this);
		this.videoStrategy = new VideoCompatStrategy(this);
		this.audioStrategy = new AudioCompatStrategy(this);
		this.moderationStrategy = new ModerationCompatStrategy(this);
		this.fineTuneStrategy = new FineTuneCompatStrategy(this);
	}

	@Override
	public String name() {
		return "openai-compat";
	}

	@Override
	protected void applyAuth(HttpRequest.Builder requestBuilder, AiConfig cfg) {
		requestBuilder.header("Authorization", "Bearer " + cfg.apiKey());
		if (cfg.organization() != null && !cfg.organization().isBlank()) {
			requestBuilder.header("OpenAI-Organization", cfg.organization());
		}
	}

	// ==================== Chat（委托策略） ====================

	@Override
	public ChatResponse chat(ChatRequest request) {
		return this.chatStrategy.chat(request);
	}

	@Override
	public void chatStream(ChatRequest request, Consumer<ChatStreamChunk> consumer) {
		this.streamStrategy.chatStream(request, consumer);
	}

	/**
	 * 构造对话请求体（默认实现委托给 {@link ChatCompatStrategy}）。
	 *
	 * <p>子类可覆盖此方法对序列化结果做平台差异化后处理（例如通义将
	 * {@code reasoning_effort} 改写为 {@code enable_thinking}、将 grounding 改写为
	 * {@code enable_search}）。覆盖时应先调用 {@code super.buildChatBody(req, stream)}
	 * 再做增删改。</p>
	 *
	 * @param req    对话请求
	 * @param stream 是否流式
	 * @return 请求体 JSON
	 */
	protected JsonObject buildChatBody(ChatRequest req, boolean stream) {
		return this.chatStrategy.buildBody(req, stream);
	}

	// ==================== Embedding（委托策略） ====================

	@Override
	public EmbeddingResponse embed(EmbeddingRequest request) {
		return this.embeddingStrategy.embed(request);
	}

	// ==================== Image（委托策略） ====================

	@Override
	public ImageResponse generate(ImageRequest request) {
		return this.imageStrategy.generate(request);
	}

	// ==================== Video（委托策略） ====================

	@Override
	public VideoResponse generate(VideoRequest request) {
		return this.videoStrategy.generate(request);
	}

	// ==================== Audio（委托策略） ====================

	@Override
	public TtsResponse synthesize(TtsRequest request) {
		return this.audioStrategy.synthesize(request);
	}

	@Override
	public SttResponse transcribe(SttRequest request) {
		return this.audioStrategy.transcribe(request);
	}

	// ==================== 模型列表（量小，保留在编排层） ====================

	@Override
	public List<Model> listModels() {
		JsonObject resp = doGet(this.modelsPath);
		List<Model> models = new ArrayList<>();
		JsonArray data = resp.has("data") ? resp.getJsonArray("data") : null;
		if (data != null) {
			for (int i = 0; i < data.size(); i++) {
				JsonObject d = data.getJsonObject(i);
				Long created = d.has("created") ? d.get("created").getAsLong() : null;
				models.add(Model.of(d.optString("id", null), created,
					d.optString("owned_by", null), d.optString("object", null), d.toString()));
			}
		}
		return models;
	}

	// ==================== Moderation（委托策略） ====================

	@Override
	public ModerationResponse moderate(ModerationRequest request) {
		return this.moderationStrategy.moderate(request);
	}

	// ==================== 微调（委托策略） ====================

	@Override
	public FineTuneResponse createFineTune(FineTuneRequest request) {
		return this.fineTuneStrategy.createFineTune(request);
	}

	@Override
	public FineTuneResponse getFineTune(String jobId) {
		return this.fineTuneStrategy.getFineTune(jobId);
	}

	@Override
	public String uploadTrainingFile(String fileName, byte[] content) {
		return this.fineTuneStrategy.uploadTrainingFile(fileName, content);
	}

	/**
	 * 解析微调任务响应（默认实现委托给 {@link FineTuneCompatStrategy}）。
	 *
	 * <p>保留为 protected 钩子：平台子类（如 Azure）覆写 {@code getFineTune} 后仍可复用
	 * 此解析逻辑。</p>
	 *
	 * @param resp    响应 JSON
	 * @param rawJson 原始报文
	 * @return 微调任务响应
	 */
	protected FineTuneResponse parseFineTuneResponse(JsonObject resp, String rawJson) {
		return this.fineTuneStrategy.parseResponse(resp, rawJson);
	}

	// ==================== 包内桥接：供同包策略访问 AbstractAiClient 的 protected 传输方法 ====================

	/** 暴露配置（策略读取 cacheStore/cacheTtl 等）。 */
	AiConfig config() {
		return this.config;
	}

	/** JSON POST，返回解析结果与原始报文。 */
	CompatPost transportPostRaw(String path, JsonObject body) {
		PostResult r = doPostRaw(path, body);
		return new CompatPost(r.json(), r.rawBody());
	}

	/** JSON POST，仅返回响应对象。 */
	JsonObject transportPost(String path, JsonObject body) {
		return doPost(path, body);
	}

	/** SSE 流式 POST。 */
	void transportPostStream(String path, JsonObject body, Consumer<JsonElement> chunkConsumer) {
		doPostStream(path, body, chunkConsumer);
	}

	/** JSON GET，返回解析结果与原始报文。 */
	CompatPost transportGetRaw(String path) {
		PostResult r = doGetRaw(path);
		return new CompatPost(r.json(), r.rawBody());
	}

	/** JSON POST，返回二进制响应体。 */
	byte[] transportPostBinary(String path, JsonObject body) {
		return doPostBinary(path, body);
	}

	/** multipart/form-data POST，返回解析结果与原始报文。 */
	CompatPost transportMultipart(String path, Map<String, String> textFields,
			String fileField, String fileName, String fileContentType, byte[] fileData) {
		PostResult r = doPostMultipart(path, textFields, fileField, fileName,
			fileContentType, fileData);
		return new CompatPost(r.json(), r.rawBody());
	}

	/** 转发 token 用量指标。 */
	void notifyUsage(String model, long promptTokens, long completionTokens, long totalTokens) {
		notifyTokenUsage(model, promptTokens, completionTokens, totalTokens);
	}

	/** 路径段百分号编码桥接。 */
	String encodeSegment(String segment) {
		return encodePathSegment(segment);
	}
}
