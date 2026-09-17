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

package com.sure.ai.client;

import java.util.List;

import com.sure.ai.model.Model;

/**
 * 模型列表（Models）客户端抽象。
 *
 * @author sureai
 * @since 0.2.0
 */
public interface ModelsClient {

	/**
	 * 列出可用模型。
	 *
	 * @return 模型列表
	 */
	List<Model> listModels();
}
