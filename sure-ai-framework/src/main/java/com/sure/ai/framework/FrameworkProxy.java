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

package com.sure.ai.framework;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.sure.ai.client.AiClient;
import com.sure.ai.exception.AiException;
import com.sure.ai.framework.annotation.AiService;
import com.sure.ai.framework.annotation.Memory;
import com.sure.ai.framework.annotation.Param;
import com.sure.ai.framework.annotation.SystemMessage;
import com.sure.ai.framework.annotation.Tool;
import com.sure.ai.framework.annotation.UserMessage;
import com.sure.ai.framework.memory.ChatMemory;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
import com.sure.ai.model.ChatStreamChunk;
import com.sure.ai.model.ToolFunction;
import com.sure.ai.model.ToolSpec;
import com.sure.ai.util.JsonMapper;
import com.sure.ai.util.JsonSchemaGenerator;

/**
 * JDK 动态代理调用处理器：把带注解的 AiService 接口方法翻译为 {@link AiClient} 调用。
 *
 * <p>本类为框架内部实现，不对外暴露；公共入口见 {@link FrameworkUtil}。</p>
 *
 * @author sureai
 * @since 2.5.0
 */
final class FrameworkProxy implements InvocationHandler {

	/** 模板占位符 {@code {name}}。 */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

	/** 返回类别。 */
	private enum ReturnKind {
		/** 返回 String（阻塞文本）。 */
		TEXT,
		/** 返回 ChatResponse（阻塞原始响应）。 */
		RAW,
		/** 返回 Stream&lt;ChatStreamChunk&gt;（流式）。 */
		STREAM,
		/** 返回 record（结构化输出）。 */
		STRUCTURED
	}

	private final Class<?> serviceClass;

	private final AiClient client;

	private final String model;

	private final Double temperature;

	private final ChatMemory memory;

	private final boolean typeMemory;

	/** 对话方法 → 返回类别。 */
	private final Map<Method, ReturnKind> chatMethods = new LinkedHashMap<>();

	/** 接口上所有 @Tool 方法（直接调用时拒绝）。 */
	private final List<Method> toolMethods = new ArrayList<>();

	/** 预计算的工具声明列表（挂到 ChatRequest.tools）。 */
	private final List<ToolSpec> toolSpecs = new ArrayList<>();

	/**
	 * 私有构造器，由 {@link #newProxy} 工厂调用。
	 *
	 * @param serviceClass 服务接口
	 * @param client      对话客户端
	 * @param model       显式模型名（可空）
	 * @param temperature  温度（可空）
	 * @param memory      会话记忆（可空）
	 */
	private FrameworkProxy(Class<?> serviceClass, AiClient client, String model,
			Double temperature, ChatMemory memory) {
		this.serviceClass = serviceClass;
		this.client = client;
		this.temperature = resolveTemperature(temperature, serviceClass);
		this.memory = memory;
		this.typeMemory = serviceClass.isAnnotationPresent(Memory.class);
		this.model = resolveModel(model, serviceClass);
		parseMethods();
	}

	/**
	 * 解析配置、校验接口并生成代理实例。
	 *
	 * @param serviceClass 服务接口
	 * @param client       对话客户端
	 * @param model        显式模型名（可空）
	 * @param temperature  温度（可空）
	 * @param memory       会话记忆（可空）
	 * @param <T>          服务类型
	 * @return 代理实例
	 */
	@SuppressWarnings("unchecked")
	static <T> T newProxy(Class<T> serviceClass, AiClient client, String model,
			Double temperature, ChatMemory memory) {
		if (!serviceClass.isInterface()) {
			throw new AiException("serviceClass 必须是接口: " + serviceClass.getName());
		}
		FrameworkProxy handler = new FrameworkProxy(serviceClass, client, model, temperature, memory);
		return (T) Proxy.newProxyInstance(serviceClass.getClassLoader(), new Class<?>[] {serviceClass},
			handler);
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.isDefault()) {
			return InvocationHandler.invokeDefault(proxy, method, args);
		}
		switch (method.getName()) {
			case "toString":
				return "FrameworkProxy[" + this.serviceClass.getName() + "]";
			case "hashCode":
				return System.identityHashCode(proxy);
			case "equals":
				return proxy == args[0];
			default:
				break;
		}
		if (this.toolMethods.contains(method)) {
			throw new IllegalStateException("工具方法由模型侧调用；本批次仅完成工具 schema 注册: "
				+ method.getName());
		}
		ReturnKind kind = this.chatMethods.get(method);
		if (kind == null) {
			throw new AiException("未识别的接口方法: " + method);
		}
		return doChat(method, args == null ? new Object[0] : args, kind);
	}

	// ==================== 方法解析与校验 ====================

	/** 遍历接口方法，登记 @Tool 工具、校验对话方法签名。 */
	private void parseMethods() {
		for (Method method : this.serviceClass.getMethods()) {
			if (isObjectMethod(method)) {
				continue;
			}
			Tool tool = method.getAnnotation(Tool.class);
			if (tool != null) {
				this.toolMethods.add(method);
				this.toolSpecs.add(buildToolSpec(method, tool));
				continue;
			}
			ReturnKind kind = classify(method);
			validateUserMessage(method);
			this.chatMethods.put(method, kind);
		}
	}

	/** 判定是否为 Object 基础方法（无需代理）。 */
	private static boolean isObjectMethod(Method method) {
		String name = method.getName();
		Class<?>[] types = method.getParameterTypes();
		return (name.equals("toString") && types.length == 0)
			|| (name.equals("hashCode") && types.length == 0)
			|| (name.equals("equals") && types.length == 1 && types[0] == Object.class);
	}

	/** 按返回类型归类，不支持的类型在创建期即抛错。 */
	private static ReturnKind classify(Method method) {
		Class<?> rt = method.getReturnType();
		if (rt == String.class) {
			return ReturnKind.TEXT;
		}
		if (rt == ChatResponse.class) {
			return ReturnKind.RAW;
		}
		if (Stream.class.isAssignableFrom(rt)) {
			return ReturnKind.STREAM;
		}
		if (rt == void.class || rt == Void.class) {
			throw new AiException("对话方法返回类型不能为 void: " + signature(method));
		}
		if (rt.isRecord()) {
			return ReturnKind.STRUCTURED;
		}
		throw new AiException("不支持的返回类型 " + rt.getName()
			+ "（仅支持 String / ChatResponse / Stream<ChatStreamChunk> / record）: " + signature(method));
	}

	/** 无 @UserMessage 模板时，仅允许单参数直传；多参数必须显式给模板。 */
	private static void validateUserMessage(Method method) {
		UserMessage um = method.getAnnotation(UserMessage.class);
		boolean hasTemplate = um != null && !um.value().isBlank();
		if (!hasTemplate && method.getParameterCount() != 1) {
			throw new AiException("多参数对话方法需标注 @UserMessage 模板: " + signature(method));
		}
	}

	/** 把 @Tool 方法签名转换为 ToolSpec（方法名/参数名 → JSON Schema）。 */
	private static ToolSpec buildToolSpec(Method method, Tool tool) {
		String name = tool.name().isBlank() ? method.getName() : tool.name();
		JsonObject props = Json.object();
		JsonArray required = Json.array();
		Parameter[] params = method.getParameters();
		for (int i = 0; i < params.length; i++) {
			String paramName = resolveParamName(params[i], i);
			JsonObject paramSchema = JsonSchemaGenerator.generate(params[i].getType());
			paramSchema.remove("$schema");
			props.set(paramName, paramSchema);
			if (params[i].getType().isPrimitive()) {
				required.add(paramName);
			}
		}
		JsonObject args = Json.object();
		args.put("type", "object");
		args.set("properties", props);
		if (required.size() > 0) {
			args.set("required", required);
		}
		return ToolSpec.of(ToolFunction.of(name, tool.description(), Json.stringify(args)));
	}

	// ==================== 调用执行 ====================

	/** 组装消息与请求并按返回类别分发。 */
	private Object doChat(Method method, Object[] args, ReturnKind kind) {
		Map<String, Object> argValues = resolveArgs(method, args);

		SystemMessage sm = method.getAnnotation(SystemMessage.class);
		String system = sm == null ? null : render(sm.value(), argValues);

		UserMessage um = method.getAnnotation(UserMessage.class);
		String userContent;
		if (um != null && !um.value().isBlank()) {
			userContent = render(um.value(), argValues);
		} else {
			userContent = String.valueOf(args[0]);
		}

		boolean memEnabled = this.typeMemory || method.isAnnotationPresent(Memory.class);
		List<ChatMessage> messages = new ArrayList<>();
		if (system != null) {
			messages.add(ChatMessage.system(system));
		}
		if (memEnabled && this.memory != null) {
			messages.addAll(this.memory.history());
		}
		messages.add(ChatMessage.user(userContent));

		ChatRequest.Builder rb = ChatRequest.builder().model(this.model).messages(messages);
		if (this.temperature != null) {
			rb.temperature(this.temperature);
		}
		if (!this.toolSpecs.isEmpty()) {
			rb.tools(this.toolSpecs);
		}

		return switch (kind) {
			case TEXT -> {
				ChatResponse resp = this.client.chat(rb.build());
				String text = resp.firstText();
				afterRound(memEnabled, userContent, text);
				yield text;
			}
			case RAW -> {
				ChatResponse resp = this.client.chat(rb.build());
				afterRound(memEnabled, userContent, resp.firstText());
				yield resp;
			}
			case STRUCTURED -> {
				attachJsonSchema(rb, method.getName(), method.getReturnType());
				ChatResponse resp = this.client.chat(rb.build());
				Object result = parseStructured(resp.firstText(), method.getReturnType());
				afterRound(memEnabled, userContent, resp.firstText());
				yield result;
			}
			case STREAM -> {
				List<ChatStreamChunk> chunks = new ArrayList<>();
				this.client.chatStream(rb.stream(true).build(), chunks::add);
				yield chunks.stream();
			}
		};
	}

	/** 结构化输出：在请求上挂载 json_schema 响应格式约束。 */
	private static void attachJsonSchema(ChatRequest.Builder rb, String methodName, Class<?> type) {
		JsonObject schema = JsonSchemaGenerator.generate(type);
		schema.remove("$schema");
		JsonObject jsonSchema = Json.object();
		jsonSchema.put("name", methodName);
		jsonSchema.set("schema", schema);
		JsonObject wrapper = Json.object();
		wrapper.put("type", "json_schema");
		wrapper.set("json_schema", jsonSchema);
		rb.responseFormat(wrapper);
	}

	/** 解析模型返回文本为 record 实例。 */
	private static Object parseStructured(String text, Class<?> type) {
		JsonElement el = Json.parse(text);
		JsonObject obj = el.getAsJsonObject();
		return JsonMapper.fromJson(obj, type);
	}

	/** 阻塞轮结束后回写 user/assistant 到会话记忆。 */
	private void afterRound(boolean memEnabled, String userContent, String assistantText) {
		if (!memEnabled || this.memory == null) {
			return;
		}
		this.memory.add(ChatMessage.user(userContent));
		if (assistantText != null) {
			this.memory.add(ChatMessage.assistant(assistantText));
		}
	}

	// ==================== 模板与参数名 ====================

	/** 收集形参名 → 实参值映射。 */
	private static Map<String, Object> resolveArgs(Method method, Object[] args) {
		Map<String, Object> map = new LinkedHashMap<>();
		Parameter[] params = method.getParameters();
		for (int i = 0; i < params.length; i++) {
			String name = resolveParamName(params[i], i);
			if (name != null) {
				map.put(name, args[i]);
			}
		}
		return map;
	}

	/** 解析单个参数的绑定名：@Param 优先，其次反射形参名。 */
	private static String resolveParamName(Parameter param, int index) {
		Param pa = param.getAnnotation(Param.class);
		if (pa != null && !pa.value().isBlank()) {
			return pa.value();
		}
		if (param.isNamePresent()) {
			return param.getName();
		}
		return null;
	}

	/** 渲染 {@code {name}} 模板；未命中占位符替换为空串。 */
	private static String render(String template, Map<String, Object> args) {
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			Object value = args.get(m.group(1));
			m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	// ==================== 配置解析 ====================

	/** 模型名：显式 builder 设置优先，其次 @AiService.model()；皆空则抛错。 */
	private static String resolveModel(String explicitModel, Class<?> serviceClass) {
		String model = explicitModel;
		if (model == null || model.isBlank()) {
			AiService ann = serviceClass.getAnnotation(AiService.class);
			if (ann != null) {
				model = ann.model();
			}
		}
		if (model == null || model.isBlank()) {
			throw new AiException("未指定模型：请使用 FrameworkUtil.builder(...).model(...) 或标注 @AiService(model=...)");
		}
		return model;
	}

	/** 温度：builder 显式设置优先，其次 @AiService.temperature()（≥0 才生效）。 */
	private static Double resolveTemperature(Double explicitTemp, Class<?> serviceClass) {
		if (explicitTemp != null) {
			return explicitTemp;
		}
		AiService ann = serviceClass.getAnnotation(AiService.class);
		if (ann != null && ann.temperature() >= 0) {
			return ann.temperature();
		}
		return null;
	}

	/** 方法签名简写，用于异常信息。 */
	private static String signature(Method method) {
		return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
	}
}
