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

/**
 * 同名工具冲突处理策略。
 *
 * <p>{@link ToolCatalog} 在注册遇到已存在的同名工具时，按本策略决定行为：</p>
 * <ul>
 *   <li>{@link #FAIL}：拒绝注册，抛出 {@link IllegalStateException}；</li>
 *   <li>{@link #OVERWRITE}：覆盖旧条目（与 {@code ToolRegistry} 既有覆盖语义一致）；</li>
 *   <li>{@link #RENAME}：自动为新条目生成唯一名（追加 {@code __n} 后缀），保证双方都保留。</li>
 * </ul>
 *
 * @author sureai
 * @since 2.9.0
 */
public enum ConflictStrategy {

	/** 冲突即失败：抛异常，不覆盖、不改名。 */
	FAIL,

	/** 冲突即覆盖：新条目替换旧条目。 */
	OVERWRITE,

	/** 冲突即改名：自动追加数字后缀生成唯一键，新旧条目共存。 */
	RENAME
}
