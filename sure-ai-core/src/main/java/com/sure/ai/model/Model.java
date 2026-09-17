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

/**
 * 模型条目（模型列表中的一条）。
 *
 * @param id       模型 ID
 * @param created  创建时间戳（秒），未知为 null
 * @param ownedBy  提供方/所有者，可能为 null
 * @param object   对象类型（通常 "model"），可能为 null
 * @param rawJson  原始 JSON
 * @author sureai
 * @since 0.2.0
 */
public record Model(String id, Long created, String ownedBy, String object, String rawJson) {

	/**
	 * 静态工厂。
	 *
	 * @param id       模型 ID
	 * @param created  创建时间戳
	 * @param ownedBy  所有者
	 * @param object   对象类型
	 * @param rawJson  原始 JSON
	 * @return 模型条目
	 */
	public static Model of(String id, Long created, String ownedBy, String object, String rawJson) {
		return new Model(id, created, ownedBy, object, rawJson);
	}
}
