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

package com.sure.ai.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * sureai 自动装配配置项（前缀 {@code sure.ai}）。
 *
 * <p>为减少类数量，各平台复用同一个通用 {@link PlatformProperties}，
 * 通过具名嵌套属性区分（{@code sure.ai.openai.*}、{@code sure.ai.qwen.*} ...）。
 * 这种写法比 {@code Map<String, PlatformProperties>} 更适合条件装配：
 * {@code @ConditionalOnProperty(prefix="sure.ai.openai", name="api-key")}
 * 需要一个明确字面量前缀。</p>
 *
 * <p>示例：<pre>{@code
 * sure:
 *   ai:
 *     openai:
 *       api-key: sk-xxx
 *       model: gpt-4o-mini
 *     qwen:
 *       api-key: sk-yyy
 *       base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 * }</pre>
 */
@ConfigurationProperties(prefix = "sure.ai")
public class SureAiProperties {

	/** OpenAI（默认 baseUrl https://api.openai.com/v1）。 */
	private PlatformProperties openai = new PlatformProperties();

	/** Azure OpenAI（baseUrl 含资源名，api-version 走平台约定）。 */
	private PlatformProperties azure = new PlatformProperties();

	/** Anthropic（Claude）。 */
	private PlatformProperties anthropic = new PlatformProperties();

	/** Gemini（Google）。 */
	private PlatformProperties gemini = new PlatformProperties();

	/** DeepSeek。 */
	private PlatformProperties deepseek = new PlatformProperties();

	/** 通义千问（DashScope 兼容模式）。 */
	private PlatformProperties qwen = new PlatformProperties();

	/** 智谱（JWT 鉴权）。 */
	private PlatformProperties zhipu = new PlatformProperties();

	/** Moonshot（Kimi）。 */
	private PlatformProperties moonshot = new PlatformProperties();

	/** 豆包 / 火山引擎。 */
	private PlatformProperties doubao = new PlatformProperties();

	/** 百度智能云（access_token 鉴权）。 */
	private PlatformProperties baidu = new PlatformProperties();

	/** Ollama（本地服务，默认 http://localhost:11434，无需真实 Key）。 */
	private PlatformProperties ollama = new PlatformProperties();

	/** xAI Grok（OpenAI 兼容协议）。 */
	private PlatformProperties grok = new PlatformProperties();

	/** Mistral AI。 */
	private PlatformProperties mistral = new PlatformProperties();

	/** Llama.cpp（本地 OpenAI 兼容服务）。 */
	private PlatformProperties llamacpp = new PlatformProperties();

	/** Cohere。 */
	private PlatformProperties cohere = new PlatformProperties();

	/** AWS Bedrock（SigV4 鉴权，独立四元组凭证，不复用 PlatformProperties）。 */
	private BedrockProperties bedrock = new BedrockProperties();

	/** RAG 相关配置（预留，当前仅声明开关）。 */
	private Rag rag = new Rag();

	/** Agent / 工具调用相关配置（预留，当前仅声明开关）。 */
	private Agent agent = new Agent();

	/**
	 * RAG 子配置（预留骨架）。
	 */
	public static class Rag {

		/** 是否启用 RAG 装配。 */
		private boolean enabled = false;

		/** 向量模型名。 */
		private String embeddingModel;

		/** 文本分块大小。 */
		private int chunkSize = 500;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getEmbeddingModel() {
			return embeddingModel;
		}

		public void setEmbeddingModel(String embeddingModel) {
			this.embeddingModel = embeddingModel;
		}

		public int getChunkSize() {
			return chunkSize;
		}

		public void setChunkSize(int chunkSize) {
			this.chunkSize = chunkSize;
		}
	}

	/**
	 * Agent 子配置（预留骨架）。
	 */
	public static class Agent {

		/** 是否启用 Agent 装配。 */
		private boolean enabled = false;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}
	}

	public PlatformProperties getOpenai() {
		return openai;
	}

	public void setOpenai(PlatformProperties openai) {
		this.openai = openai;
	}

	public PlatformProperties getAzure() {
		return azure;
	}

	public void setAzure(PlatformProperties azure) {
		this.azure = azure;
	}

	public PlatformProperties getAnthropic() {
		return anthropic;
	}

	public void setAnthropic(PlatformProperties anthropic) {
		this.anthropic = anthropic;
	}

	public PlatformProperties getGemini() {
		return gemini;
	}

	public void setGemini(PlatformProperties gemini) {
		this.gemini = gemini;
	}

	public PlatformProperties getDeepseek() {
		return deepseek;
	}

	public void setDeepseek(PlatformProperties deepseek) {
		this.deepseek = deepseek;
	}

	public PlatformProperties getQwen() {
		return qwen;
	}

	public void setQwen(PlatformProperties qwen) {
		this.qwen = qwen;
	}

	public PlatformProperties getZhipu() {
		return zhipu;
	}

	public void setZhipu(PlatformProperties zhipu) {
		this.zhipu = zhipu;
	}

	public PlatformProperties getMoonshot() {
		return moonshot;
	}

	public void setMoonshot(PlatformProperties moonshot) {
		this.moonshot = moonshot;
	}

	public PlatformProperties getDoubao() {
		return doubao;
	}

	public void setDoubao(PlatformProperties doubao) {
		this.doubao = doubao;
	}

	public PlatformProperties getBaidu() {
		return baidu;
	}

	public void setBaidu(PlatformProperties baidu) {
		this.baidu = baidu;
	}

	public PlatformProperties getOllama() {
		return ollama;
	}

	public void setOllama(PlatformProperties ollama) {
		this.ollama = ollama;
	}

	public PlatformProperties getGrok() {
		return grok;
	}

	public void setGrok(PlatformProperties grok) {
		this.grok = grok;
	}

	public PlatformProperties getMistral() {
		return mistral;
	}

	public void setMistral(PlatformProperties mistral) {
		this.mistral = mistral;
	}

	public PlatformProperties getLlamacpp() {
		return llamacpp;
	}

	public void setLlamacpp(PlatformProperties llamacpp) {
		this.llamacpp = llamacpp;
	}

	public PlatformProperties getCohere() {
		return cohere;
	}

	public void setCohere(PlatformProperties cohere) {
		this.cohere = cohere;
	}

	public BedrockProperties getBedrock() {
		return bedrock;
	}

	public void setBedrock(BedrockProperties bedrock) {
		this.bedrock = bedrock;
	}

	public Rag getRag() {
		return rag;
	}

	public void setRag(Rag rag) {
		this.rag = rag;
	}

	public Agent getAgent() {
		return agent;
	}

	public void setAgent(Agent agent) {
		this.agent = agent;
	}
}
