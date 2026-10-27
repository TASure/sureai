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

import com.sure.ai.model.ModerationRequest;
import com.sure.ai.model.ModerationResponse;

/**
 * 内容审核（Moderation）客户端抽象。
 *
 * @author sureai
 * @since 0.2.0
 */
public interface ModerationClient {

	/**
	 * 审核一段文本。
	 *
	 * @param request 审核请求
	 * @return 审核响应
	 */
	ModerationResponse moderate(ModerationRequest request);
}
