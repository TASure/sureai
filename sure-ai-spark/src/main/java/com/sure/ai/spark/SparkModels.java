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

package com.sure.ai.spark;

/**
 * 讯飞星火 OpenAI 兼容端点当前主流模型 ID 常量。
 *
 * <p>OpenAI 兼容端点使用短模型名（注意：不是 {@code spark-lite} 这类带前缀的写法）。
 * 可向 {@code model} 字段传入任意模型字符串，本类常量仅为便捷参考；
 * 最新可用模型列表以官方文档为准：
 * <a href="https://www.xfyun.cn/doc/spark/Web.html">星火 Web API 文档</a></p>
 *
 * @author sureai
 * @since 1.9.0
 */
public final class SparkModels {

	private SparkModels() {
		throw new AssertionError("No instances");
	}

	/** lite：星火 Lite 模型（OpenAI 兼容端点，永久免费）。 */
	public static final String LITE = "lite";

	/** pro：星火 Pro 模型。 */
	public static final String PRO = "pro";

	/** max：星火 Max 模型（对应 Spark Max / generalv3.5 级别）。 */
	public static final String MAX = "max";

	/** general：星火通用模型（V1.5 级别）。 */
	public static final String GENERAL = "general";
}
