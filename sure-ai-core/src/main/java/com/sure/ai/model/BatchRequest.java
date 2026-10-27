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

package com.sure.ai.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.tool.lang.Assert;

/**
 * 批处理（Batch）请求。
 *
 * <p>将一组对话请求批量提交给平台异步处理。输入二选一：</p>
 * <ul>
 *   <li>{@code inputFileId}：已上传的 JSONL 输入文件 ID；</li>
 *   <li>{@code requests}：直接传入一组 {@link ChatRequest}，由平台客户端负责封装为文件。</li>
 * </ul>
 *
 * <p>model 必填；{@code inputFileId} 与 {@code requests} 至少提供其一。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class BatchRequest {

	private final String model;
	private final String inputFileId;
	private final List<ChatRequest> requests;
	private final String completionWindow;
	private final Map<String, Object> metadata;
	private final Map<String, Object> extra;

	private BatchRequest(Builder b) {
		this.model = b.model;
		this.inputFileId = b.inputFileId;
		this.requests = b.requests == null ? List.of() : List.copyOf(b.requests);
		this.completionWindow = b.completionWindow;
		this.metadata = b.metadata == null ? Map.of() : Map.copyOf(b.metadata);
		this.extra = b.extra == null ? Map.of() : Map.copyOf(b.extra);
	}

	/**
	 * 创建 Builder。
	 *
	 * @return Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String model;
		private String inputFileId;
		private List<ChatRequest> requests;
		private String completionWindow;
		private Map<String, Object> metadata;
		private Map<String, Object> extra;

		private Builder() {
		}

		/**
		 * 设置批处理模型。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 设置已上传的输入文件 ID。
		 *
		 * @param inputFileId 输入文件 ID
		 * @return this
		 */
		public Builder inputFileId(String inputFileId) {
			this.inputFileId = inputFileId;
			return this;
		}

		/**
		 * 设置待批处理的对话请求列表。
		 *
		 * @param requests 对话请求列表
		 * @return this
		 */
		public Builder requests(List<ChatRequest> requests) {
			this.requests = requests;
			return this;
		}

		/**
		 * 追加一条对话请求。
		 *
		 * @param request 对话请求
		 * @return this
		 */
		public Builder addRequest(ChatRequest request) {
			if (this.requests == null) {
				this.requests = new ArrayList<>();
			}
			this.requests.add(request);
			return this;
		}

		/**
		 * 设置完成时间窗（如 "24h"）。
		 *
		 * @param completionWindow 完成时间窗
		 * @return this
		 */
		public Builder completionWindow(String completionWindow) {
			this.completionWindow = completionWindow;
			return this;
		}

		/**
		 * 设置元数据。
		 *
		 * @param key   键
		 * @param value 值
		 * @return this
		 */
		public Builder metadata(String key, Object value) {
			if (this.metadata == null) {
				this.metadata = new LinkedHashMap<>();
			}
			this.metadata.put(key, value);
			return this;
		}

		/**
		 * 追加透传字段。
		 *
		 * @param key   键
		 * @param value 值
		 * @return this
		 */
		public Builder extra(String key, Object value) {
			if (this.extra == null) {
				this.extra = new LinkedHashMap<>();
			}
			this.extra.put(key, value);
			return this;
		}

		/**
		 * 构建请求，校验必填字段。
		 *
		 * @return 请求
		 */
		public BatchRequest build() {
			Assert.notBlank(this.model, "model 不能为 blank");
			Assert.isTrue(this.inputFileId != null || (this.requests != null && !this.requests.isEmpty()),
				"inputFileId 与 requests 至少提供其一");
			return new BatchRequest(this);
		}
	}

	/** 批处理模型名。 */
	public String model() {
		return this.model;
	}

	/** 输入文件 ID，直接传 requests 时为 null。 */
	public String inputFileId() {
		return this.inputFileId;
	}

	/** 待批处理的对话请求列表（不可变）。 */
	public List<ChatRequest> requests() {
		return this.requests;
	}

	/** 完成时间窗。 */
	public String completionWindow() {
		return this.completionWindow;
	}

	/** 元数据。 */
	public Map<String, Object> metadata() {
		return this.metadata;
	}

	/** 透传字段。 */
	public Map<String, Object> extra() {
		return this.extra;
	}
}
