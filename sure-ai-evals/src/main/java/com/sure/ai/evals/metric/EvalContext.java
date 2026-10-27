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

package com.sure.ai.evals.metric;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评估运行上下文：被测模型名 + 运行级属性，在一次 run 内对所有用例 / 指标共享。
 *
 * <p>不可变。指标所需的外部客户端（裁判 {@code AiClient}、{@code EmbeddingClient}）
 * 通过各指标构造器注入，不放在上下文中；上下文只承载运行级元数据与跨指标临时信息
 * （如数据集名、运行 ID、温度等）。</p>
 *
 * @author sureai
 * @since 2.8.0
 */
public final class EvalContext {

	private final String model;
	private final Map<String, Object> attributes;

	private EvalContext(String model, Map<String, Object> attributes) {
		this.model = model;
		this.attributes = Map.copyOf(attributes);
	}

	/**
	 * 创建只含模型名的上下文。
	 *
	 * @param model 被测模型名
	 * @return 上下文
	 */
	public static EvalContext create(String model) {
		return new EvalContext(model, Map.of());
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
	 * 被测模型名。
	 *
	 * @return 模型名
	 */
	public String model() {
		return this.model;
	}

	/**
	 * 运行级属性（不可变）。
	 *
	 * @return 属性表
	 */
	public Map<String, Object> attributes() {
		return this.attributes;
	}

	/**
	 * 读取属性，缺失返回 null。
	 *
	 * @param key 键
	 * @return 值，或 null
	 */
	public Object attribute(String key) {
		return this.attributes.get(key);
	}

	/**
	 * 读取字符串属性，缺失或非字符串返回默认值。
	 *
	 * @param key          键
	 * @param defaultValue 默认值
	 * @return 字符串值
	 */
	public String attribute(String key, String defaultValue) {
		Object value = this.attributes.get(key);
		return value instanceof String s ? s : defaultValue;
	}

	/**
	 * Builder。
	 */
	public static final class Builder {

		private String model;
		private final Map<String, Object> attributes = new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * 设置被测模型名。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 添加属性。
		 *
		 * @param key   键
		 * @param value 值
		 * @return this
		 */
		public Builder put(String key, Object value) {
			this.attributes.put(key, value);
			return this;
		}

		/**
		 * 构建上下文。
		 *
		 * @return 上下文
		 */
		public EvalContext build() {
			return new EvalContext(this.model, this.attributes);
		}
	}
}
