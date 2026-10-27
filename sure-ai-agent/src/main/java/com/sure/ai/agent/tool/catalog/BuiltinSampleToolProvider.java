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

import java.util.List;

import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;

/**
 * 内置本地 SPI 示例提供者：贡献两个纯内存、零网络的样例工具，
 * 用于演示 {@link ToolProvider} ServiceLoader 登记与加载链路。
 *
 * <ul>
 *   <li>{@code sample.echo}：回显输入文本；</li>
 *   <li>{@code sample.ping}：固定返回 {@code pong}。</li>
 * </ul>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class BuiltinSampleToolProvider implements ToolProvider {

	/** echo 工具参数 Schema。 */
	private static final String ECHO_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "text": { "type": "string", "description": "待回显文本" }
			  },
			  "required": ["text"]
			}
			""";

	/** ping 工具参数 Schema（无参）。 */
	private static final String PING_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

	@Override
	public List<ToolContribution> provide() {
		ToolFunction echo = ToolFunction.of("sample.echo", "回显输入文本的样例工具", ECHO_SCHEMA);
		ToolMetadata echoMeta = ToolMetadata.builder("sample.echo")
			.description("SPI 示例：回显")
			.version("1.0.0")
			.addTag("sample")
			.addTag("demo")
			.riskLevel(RiskLevel.LOW)
			.source(ToolSource.LOCAL)
			.build();

		ToolFunction ping = ToolFunction.of("sample.ping", "存活探测样例工具，固定返回 pong", PING_SCHEMA);
		ToolMetadata pingMeta = ToolMetadata.builder("sample.ping")
			.description("SPI 示例：ping")
			.version("1.0.0")
			.addTag("sample")
			.riskLevel(RiskLevel.LOW)
			.source(ToolSource.LOCAL)
			.build();

		return List.of(
			new ToolContribution(echo, BuiltinSampleToolProvider::echo, echoMeta),
			new ToolContribution(ping, BuiltinSampleToolProvider::ping, pingMeta));
	}

	/** echo 处理器：回显 text 参数。 */
	private static String echo(JsonObject arguments) {
		String text = arguments == null ? "" : arguments.optString("text", "");
		return "echo:" + text;
	}

	/** ping 处理器：固定 pong。 */
	private static String ping(JsonObject arguments) {
		return "pong";
	}
}
