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

package com.sure.ai.agent.skill;

import java.util.Collection;

import com.sure.ai.agent.tool.ToolHandler;
import com.sure.ai.agent.tool.ToolRegistry;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ToolFunction;
import com.sure.tool.lang.Assert;

/**
 * 技能工具化适配器：把 {@link Skill} 包装成 {@link ToolFunction} + {@link ToolHandler}，
 * 注册进 {@link ToolRegistry} 后即可被 ReAct 循环调用。
 *
 * <p><b>语义（对齐规范「技能=可加载的指令与资源」）</b>：模型调用技能工具时，
 * 执行期按需加载 SKILL.md 正文并作为工具结果回灌模型，供模型按技能指导行动；
 * 传入可选 {@code resource} 参数时改为返回指定相对资源文本。</p>
 *
 * <p><b>安全边界</b>：本批<b>不执行</b>技能内 scripts/ 脚本（避免任意脚本执行），
 * 仅返回指令与文本资源；{@code allowed-tools} 仅作为元数据保留，
 * 不在此层做工具白名单强制。</p>
 *
 * @author sureai
 * @since 2.9.0
 */
public final class SkillTool {

	/** 可选参数：按需读取的资源相对路径。 */
	public static final String PARAM_RESOURCE = "resource";

	/** 工具参数 JSON Schema（仅一个可选 string 参数）。 */
	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "resource": {
			      "type": "string",
			      "description": "可选：技能内相对资源路径（如 references/guide.md）；缺省返回 SKILL.md 正文指令"
			    }
			  }
			}
			""";

	/** 私有构造（静态工具类）。 */
	private SkillTool() {
	}

	/**
	 * 构造工具函数声明：工具名=技能名，描述=manifest.description。
	 *
	 * @param skill 技能
	 * @return 工具函数声明
	 */
	public static ToolFunction toToolFunction(Skill skill) {
		Assert.notNull(skill, "skill must not be null");
		return ToolFunction.of(skill.name(), skill.manifest().description(), SCHEMA);
	}

	/**
	 * 构造执行处理器：无参数返回正文；带 {@code resource} 返回指定资源。
	 *
	 * @param skill 技能
	 * @return 工具处理器
	 */
	public static ToolHandler toHandler(Skill skill) {
		Assert.notNull(skill, "skill must not be null");
		return (JsonObject arguments) -> {
			String resource = arguments == null
					? null
					: arguments.optString(PARAM_RESOURCE, null);
			if (resource != null && !resource.isBlank()) {
				return skill.loadResource(resource);
			}
			return skill.loadBody();
		};
	}

	/**
	 * 把单个技能注册进注册中心。
	 *
	 * @param skill    技能
	 * @param registry 工具注册中心
	 */
	public static void register(Skill skill, ToolRegistry registry) {
		Assert.notNull(skill, "skill must not be null");
		Assert.notNull(registry, "registry must not be null");
		registry.register(toToolFunction(skill), toHandler(skill));
	}

	/**
	 * 批量注册技能。
	 *
	 * @param skills   技能集合
	 * @param registry 工具注册中心
	 */
	public static void registerAll(Collection<Skill> skills, ToolRegistry registry) {
		Assert.notNull(skills, "skills must not be null");
		Assert.notNull(registry, "registry must not be null");
		for (Skill skill : skills) {
			register(skill, registry);
		}
	}
}
