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

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonObject;

/**
 * OpenAI 兼容协议各能力域策略共享的 JSON 拼装小工具。
 *
 * <p>仅承担"非空字段写入"这一跨域复用逻辑，无状态、不可实例化。</p>
 *
 * @author sureai
 * @since 1.4.0
 */
final class CompatJson {

	/** 私有构造器。 */
	private CompatJson() {
		throw new AssertionError("No instances");
	}

	/** 非空字段写入：value 为 null 时跳过。 */
	static void putIfNotNull(JsonObject body, String key, Object value) {
		if (value != null) {
			body.put(key, Json.toElement(value));
		}
	}
}
