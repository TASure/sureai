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

import com.sure.ai.client.AiClient;
import com.sure.ai.framework.memory.ChatMemory;
import com.sure.tool.lang.Assert;

/**
 * 声明式编排层静态入口：一行创建「接口即服务」代理。
 *
 * <p>典型用法：</p>
 * <pre>
 *   {@literal @}AiService(model = "gpt-4o-mini")
 *   interface Assistant {
 *       {@literal @}SystemMessage("你是{role}助手")
 *       {@literal @}UserMessage("请总结：{text}")
 *       String summarize(String role, String text);
 *   }
 *
 *   Assistant ai = FrameworkUtil.create(Assistant.class, client);
 *   String result = ai.summarize("法务", "合同正文……");
 * </pre>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class FrameworkUtil {

	/** 私有构造器，工具类禁止实例化。 */
	private FrameworkUtil() {
		throw new AssertionError("No instances");
	}

	/**
	 * 一行创建 AiService 代理（模型来自 {@code @AiService.model()}）。
	 *
	 * @param serviceClass 服务接口
	 * @param client       对话客户端
	 * @param <T>          服务类型
	 * @return 代理实例
	 */
	public static <T> T create(Class<T> serviceClass, AiClient client) {
		return builder(serviceClass, client).build();
	}

	/**
	 * 一行创建 AiService 代理（显式指定默认模型）。
	 *
	 * @param serviceClass 服务接口
	 * @param client       对话客户端
	 * @param model        默认模型名
	 * @param <T>          服务类型
	 * @return 代理实例
	 */
	public static <T> T create(Class<T> serviceClass, AiClient client, String model) {
		return builder(serviceClass, client).model(model).build();
	}

	/**
	 * 开启流式 Builder，用于注入模型/温度/会话记忆等高级配置。
	 *
	 * @param serviceClass 服务接口
	 * @param client       对话客户端
	 * @param <T>          服务类型
	 * @return Builder
	 */
	public static <T> Builder<T> builder(Class<T> serviceClass, AiClient client) {
		return new Builder<>(serviceClass, client);
	}

	/**
	 * AiService 代理 Builder。
	 *
	 * @param <T> 服务类型
	 */
	public static final class Builder<T> {

		private final Class<T> serviceClass;

		private final AiClient client;

		private String model;

		private Double temperature;

		private ChatMemory memory;

		/**
		 * 由静态入口构造。
		 *
		 * @param serviceClass 服务接口
		 * @param client       对话客户端
		 */
		Builder(Class<T> serviceClass, AiClient client) {
			Assert.notNull(serviceClass, "serviceClass must not be null");
			Assert.notNull(client, "client must not be null");
			this.serviceClass = serviceClass;
			this.client = client;
		}

		/**
		 * 显式指定默认模型（优先于 {@code @AiService.model()}）。
		 *
		 * @param model 模型名
		 * @return this
		 */
		public Builder<T> model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * 指定采样温度（优先于 {@code @AiService.temperature()}）。
		 *
		 * @param temperature 温度
		 * @return this
		 */
		public Builder<T> temperature(double temperature) {
			this.temperature = temperature;
			return this;
		}

		/**
		 * 注入会话记忆（配合 {@code @Memory} 开启历史注入与回写）。
		 *
		 * @param memory 会话记忆
		 * @return this
		 */
		public Builder<T> memory(ChatMemory memory) {
			this.memory = memory;
			return this;
		}

		/**
		 * 解析配置并生成 JDK 动态代理。
		 *
		 * @return 代理实例
		 */
		public T build() {
			return FrameworkProxy.newProxy(this.serviceClass, this.client, this.model,
				this.temperature, this.memory);
		}
	}
}
