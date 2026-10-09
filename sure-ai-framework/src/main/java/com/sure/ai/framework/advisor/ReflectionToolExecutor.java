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

package com.sure.ai.framework.advisor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sure.ai.framework.annotation.Param;
import com.sure.ai.framework.annotation.Tool;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * 内置轻量工具执行器：反射调用 AiService 接口上的 {@link Tool @Tool} 方法。
 *
 * <p>这是 framework 为避免反向依赖 agent 模块而自建的最小执行器（对齐批次 1 自建
 * ChatMemory 的分层取向）：</p>
 * <ul>
 *   <li>按工具名（{@code @Tool.name()} 或方法名）找到 {@link Method}；</li>
 *   <li>把模型的 {@code argumentsJson} 按形参名（{@code @Param} 优先，否则反射形参名）
 *       绑定到形参，支持 String / 基本类型及其包装；</li>
 *   <li>{@code default} 方法经 {@link InvocationHandler#invokeDefault} 在代理实例上执行；
 *       抽象 @Tool 方法无方法体，执行结果为错误文本回灌模型。</li>
 * </ul>
 *
 * <p>所有异常都收敛为错误文本返回，不向外抛（配合 {@link ToolCallingAdvisor} 的失败隔离）。
 * 线程安全：不可变，可并发共享。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
public final class ReflectionToolExecutor implements ToolExecutor {

	/** 工具名 → 方法。 */
	private final Map<String, Method> methods;

	/** 代理实例（default 方法在其上 invokeDefault）。 */
	private final Object proxy;

	/**
	 * 构造。
	 *
	 * @param proxy      动态代理实例（default 方法执行载体）
	 * @param toolMethods 接口上所有 @Tool 方法
	 */
	public ReflectionToolExecutor(Object proxy, List<Method> toolMethods) {
		this.proxy = proxy;
		Map<String, Method> map = new LinkedHashMap<>();
		for (Method m : toolMethods) {
			Tool tool = m.getAnnotation(Tool.class);
			String name = (tool != null && !tool.name().isBlank()) ? tool.name() : m.getName();
			map.put(name, m);
		}
		this.methods = Map.copyOf(map);
	}

	@Override
	public String execute(String toolName, String argumentsJson) {
		Method method = this.methods.get(toolName);
		if (method == null) {
			return "工具未注册: " + toolName;
		}
		JsonObject args = parseArgs(argumentsJson);
		Object[] bound;
		try {
			bound = bind(method, args);
		} catch (RuntimeException e) {
			return "工具参数绑定失败 " + toolName + ": " + e.getMessage();
		}
		try {
			Object result;
			if (method.isDefault()) {
				result = InvocationHandler.invokeDefault(this.proxy, method, bound);
			} else {
				result = method.invoke(this.proxy, bound);
			}
			return result == null ? "" : String.valueOf(result);
		} catch (Throwable t) {
			Throwable cause = t.getCause() != null ? t.getCause() : t;
			return "工具执行异常 " + toolName + ": " + cause.getMessage();
		}
	}

	/** 解析 argumentsJson 为对象；空 / 非法退化为空对象。 */
	private static JsonObject parseArgs(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return Json.object();
		}
		try {
			JsonElement el = Json.parse(argumentsJson);
			if (el != null && el.isObject()) {
				return el.getAsJsonObject();
			}
		} catch (RuntimeException ignored) {
			// 落到空对象
		}
		return Json.object();
	}

	/** 按形参顺序绑定实参。 */
	private static Object[] bind(Method method, JsonObject args) {
		Parameter[] params = method.getParameters();
		Object[] out = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			out[i] = coerce(params[i], args);
		}
		return out;
	}

	/** 解析单个形参值。 */
	private static Object coerce(Parameter p, JsonObject args) {
		String name = resolveParamName(p);
		Class<?> t = p.getType();
		if (name == null) {
			return defaultValue(t);
		}
		if (!args.has(name)) {
			return defaultValue(t);
		}
		if (t == String.class) {
			return args.getString(name);
		}
		if (t == int.class || t == Integer.class) {
			return args.getInt(name);
		}
		if (t == long.class || t == Long.class) {
			return (long) args.getDouble(name);
		}
		if (t == double.class || t == Double.class || t == float.class || t == Float.class) {
			return args.getDouble(name);
		}
		if (t == boolean.class || t == Boolean.class) {
			return args.getBoolean(name);
		}
		// 其余类型暂不支持，回退字符串
		return args.getString(name);
	}

	/** 缺省值（基本类型 0/false，引用类型 null）。 */
	private static Object defaultValue(Class<?> t) {
		if (t == boolean.class) {
			return false;
		}
		if (t == int.class || t == long.class || t == short.class || t == byte.class) {
			return 0;
		}
		if (t == float.class || t == double.class) {
			return 0d;
		}
		return null;
	}

	/** 形参绑定名：@Param 优先，否则反射形参名。 */
	private static String resolveParamName(Parameter p) {
		Param pa = p.getAnnotation(Param.class);
		if (pa != null && !pa.value().isBlank()) {
			return pa.value();
		}
		return p.isNamePresent() ? p.getName() : null;
	}
}
