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

package com.sure.ai.anthropic;

/**
 * Anthropic Claude 模型 ID 常量。
 *
 * <p>收录 2026 年当前可用的主力模型；可传入任意模型字符串（包括已废弃或预览模型），
 * 以官方文档模型列表为准：
 * <a href="https://docs.anthropic.com/en/docs/about-claude/models/overview">Models overview</a>。</p>
 *
 * @author sureai
 * @since 0.1.0
 */
public final class AnthropicModels {

	/** Claude Opus 4.7 — 最强推理与编码能力。 */
	public static final String CLAUDE_OPUS_4_7 = "claude-opus-4-7";

	/** Claude Sonnet 4.6 — 均衡性能与成本。 */
	public static final String CLAUDE_SONNET_4_6 = "claude-sonnet-4-6";

	/** Claude Haiku 4.5 — 快速、低成本。 */
	public static final String CLAUDE_HAIKU_4_5 = "claude-haiku-4-5";

	/** Claude Opus 4.6 — 上一代 Opus。 */
	public static final String CLAUDE_OPUS_4_6 = "claude-opus-4-6";

	/** Claude Sonnet 4.5 — 上一代 Sonnet。 */
	public static final String CLAUDE_SONNET_4_5 = "claude-sonnet-4-5";

	private AnthropicModels() {
		throw new AssertionError("No instances");
	}
}
