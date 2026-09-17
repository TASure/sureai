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

package com.sure.ai.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sure.ai.exception.AiException;
import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;
import com.sure.ai.internal.json.JsonPrimitive;

/**
 * JSON 与 Java record 之间的反射式便捷映射工具。
 *
 * <p>零第三方依赖，面向 record 类做<b>按组件名</b>的双向映射：</p>
 * <ul>
 *   <li>{@link #fromJson(JsonObject, Class)}：读取 record 规范构造器的组件名，
 *       从 {@link JsonObject} 同名字段取值并按组件类型转换后调用规范构造器；</li>
 *   <li>{@link #toJson(Object)}：读取 record 组件访问器，递归转为 {@link JsonObject}。</li>
 * </ul>
 *
 * <p>支持的字段类型：String、int/Integer、long/Long、double/Double、boolean/Boolean、
 * 嵌套 record、List（标量或 record 元素）、Map。不支持的类型会抛 {@link AiException}。</p>
 *
 * @author sureai
 * @since 0.2.0
 */
public final class JsonMapper {

	/** 私有构造器。 */
	private JsonMapper() {
		throw new AssertionError("No instances");
	}

	/**
	 * 将 JsonObject 反序列化为 record 实例。
	 *
	 * @param obj   源 JSON 对象
	 * @param clazz 目标 record 类型
	 * @param <T>   目标类型
	 * @return record 实例
	 * @throws AiException 不支持的类型或反射失败时抛出
	 */
	public static <T> T fromJson(JsonObject obj, Class<T> clazz) {
		if (obj == null) {
			return null;
		}
		if (JsonObject.class.isAssignableFrom(clazz)) {
			return clazz.cast(obj);
		}
		if (!clazz.isRecord()) {
			throw new AiException("JsonMapper 仅支持 record 反序列化: " + clazz.getName());
		}
		try {
			RecordComponent[] comps = clazz.getRecordComponents();
			Class<?>[] types = new Class<?>[comps.length];
			Object[] args = new Object[comps.length];
			for (int i = 0; i < comps.length; i++) {
				RecordComponent comp = comps[i];
				types[i] = comp.getType();
				JsonElement el = obj.get(comp.getName());
				args[i] = convert(el, comp.getType(), comp.getGenericType());
			}
			Constructor<T> ctor = clazz.getDeclaredConstructor(types);
			ctor.setAccessible(true);
			return ctor.newInstance(args);
		} catch (ReflectiveOperationException ex) {
			throw new AiException("JsonMapper 反序列化失败: " + clazz.getName(), ex);
		}
	}

	/**
	 * 将 Java 对象（record / 标量 / List / Map）序列化为 JSON 元素。
	 *
	 * @param obj Java 对象
	 * @return JSON 元素
	 */
	public static JsonElement toJson(Object obj) {
		return toElement(obj);
	}

	/**
	 * 将 record 序列化为 JsonObject。
	 *
	 * @param recordObj record 实例
	 * @return JSON 对象
	 */
	public static JsonObject toJsonObject(Object recordObj) {
		if (recordObj == null) {
			return null;
		}
		return toElement(recordObj).getAsJsonObject();
	}

	// ==================== 内部实现 ====================

	/** 递归转换 JsonElement 为目标类型。 */
	private static Object convert(JsonElement el, Class<?> type, Type genericType) {
		if (el == null || el.isNull()) {
			return primitiveDefault(type);
		}
		if (type == String.class) {
			return el.getAsString();
		}
		if (type == int.class || type == Integer.class) {
			return el.getAsInt();
		}
		if (type == long.class || type == Long.class) {
			return el.getAsLong();
		}
		if (type == double.class || type == Double.class) {
			return el.getAsDouble();
		}
		if (type == boolean.class || type == Boolean.class) {
			return el.getAsBoolean();
		}
		if (type.isRecord()) {
			return fromJson(el.getAsJsonObject(), type.asSubclass(Record.class));
		}
		if (List.class.isAssignableFrom(type)) {
			Class<?> elemType = listElementType(genericType);
			JsonArray arr = el.getAsJsonArray();
			List<Object> out = new ArrayList<>(arr.size());
			for (int i = 0; i < arr.size(); i++) {
				out.add(convert(arr.get(i), elemType, elemType));
			}
			return out;
		}
		throw new AiException("JsonMapper 不支持的字段类型: " + type.getName());
	}

	/** 解析 List 的泛型元素类型，无法解析时按 Object 处理（退回标量）。 */
	private static Class<?> listElementType(Type genericType) {
		if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
			Type arg = pt.getActualTypeArguments()[0];
			if (arg instanceof Class<?> c) {
				return c;
			}
		}
		return Object.class;
	}

	/** 原始类型缺失时的默认值，引用类型缺失返回 null。 */
	private static Object primitiveDefault(Class<?> type) {
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == double.class) {
			return 0.0d;
		}
		if (type == boolean.class) {
			return false;
		}
		return null;
	}

	/** 递归将 Java 值转为 JsonElement。 */
	private static JsonElement toElement(Object obj) {
		if (obj == null) {
			return JsonPrimitive.jsonNull();
		}
		if (obj instanceof JsonElement el) {
			return el;
		}
		if (obj instanceof String s) {
			return new JsonPrimitive(s);
		}
		if (obj instanceof Number n) {
			return new JsonPrimitive(n);
		}
		if (obj instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (obj instanceof Record rec) {
			return recordToJson(rec);
		}
		if (obj instanceof List<?> list) {
			JsonArray arr = new JsonArray();
			for (Object o : list) {
				arr.add(toElement(o));
			}
			return arr;
		}
		if (obj instanceof Map<?, ?> map) {
			JsonObject jo = new JsonObject();
			for (Map.Entry<?, ?> e : map.entrySet()) {
				jo.put(String.valueOf(e.getKey()), toElement(e.getValue()));
			}
			return jo;
		}
		return new JsonPrimitive(String.valueOf(obj));
	}

	/** 反射读取 record 组件访问器，组装为 JsonObject。 */
	private static JsonObject recordToJson(Record rec) {
		JsonObject jo = Json.object();
		Class<?> clazz = rec.getClass();
		for (RecordComponent comp : clazz.getRecordComponents()) {
			try {
				Method accessor = comp.getAccessor();
				accessor.setAccessible(true);
				jo.put(comp.getName(), toElement(accessor.invoke(rec)));
			} catch (ReflectiveOperationException ex) {
				throw new AiException("JsonMapper 序列化失败: " + clazz.getName(), ex);
			}
		}
		return jo;
	}
}
