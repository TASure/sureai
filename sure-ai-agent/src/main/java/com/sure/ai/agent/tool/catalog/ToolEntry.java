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

package com.sure.ai.agent.tool.catalog;

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;
import com.sure.tool.lang.Assert;

/**
 * 工具市场条目：工具函数声明 + 执行处理器 + 市场元数据三合一。
 *
 * @param function 工具函数声明
 * @param handler  执行处理器
 * @param metadata 市场元数据
 * @author sureai
 * @since 2.9.0
 */
public record ToolEntry(ToolFunction function, ToolHandler handler, ToolMetadata metadata) {

	/** 紧凑构造：非空校验。 */
	public ToolEntry {
		Assert.notNull(function, "function must not be null");
		Assert.notNull(handler, "handler must not be null");
		Assert.notNull(metadata, "metadata must not be null");
	}

	/**
	 * 便捷取工具名。
	 *
	 * @return 工具名
	 */
	public String name() {
		return this.function.name();
	}

	/**
	 * 包装为 {@link ToolSpec}，供 {@code ChatRequest.tools} 使用。
	 *
	 * @return 工具声明
	 */
	public ToolSpec spec() {
		return ToolSpec.of(this.function);
	}
}
