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
 * 多模态消息片段密封接口。
 *
 * @author sureai
 * @since 0.1.0
 */
public sealed interface MessagePart permits TextPart, ImagePart {

	/**
	 * 返回片段类型："text" 或 "image_url"。
	 *
	 * @return 类型字符串
	 */
	String type();
}
