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

package com.sure.ai.agent.tool.builtin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;

/**
 * 日期时间工具：按格式与时区返回当前时间字符串。
 *
 * @author sureai
 * @since 1.1.0
 */
public final class DateTimeTool implements ToolHandler {

	/** 工具名。 */
	public static final String TOOL_NAME = "datetime";

	private static final String DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss";

	/**
	 * 返回工具函数声明。
	 *
	 * @return ToolFunction
	 */
	public static ToolFunction toToolFunction() {
		return ToolFunction.of(TOOL_NAME,
			"Return the current time formatted according to the given pattern and zone.",
			"""
			{
			  "type": "object",
			  "properties": {
			    "format": { "type": "string", "description": "DateTimeFormatter pattern, default yyyy-MM-dd HH:mm:ss" },
			    "zone": { "type": "string", "description": "ZoneId, e.g. Asia/Shanghai, default system zone" }
			  }
			}
			""");
	}

	@Override
	public String execute(JsonObject args) {
		String format = args == null ? DEFAULT_FORMAT : args.optString("format", DEFAULT_FORMAT);
		ZoneId zone;
		if (args != null && args.has("zone")) {
			try {
				zone = ZoneId.of(args.getString("zone"));
			} catch (RuntimeException e) {
				return "Error: invalid time zone: " + args.getString("zone");
			}
		} else {
			zone = ZoneId.systemDefault();
		}
		try {
			DateTimeFormatter fmt = DateTimeFormatter.ofPattern(format).withZone(zone);
			return fmt.format(Instant.now());
		} catch (RuntimeException e) {
			return "Error: invalid date format: " + format;
		}
	}
}
