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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonElement;
import com.sure.ai.internal.json.JsonObject;

/**
 * 基于 Java 反射、零第三方依赖的 JSON Schema（Draft 2020-12）生成器。
 *
 * <p>从普通 POJO / record 的结构推导出描述其字段的 JSON Schema，供 MCP server 自动生成
 * tool 的 {@code inputSchema}，亦可作为通用参数描述使用。仅依赖 JDK 反射与 core 自带的
 * {@link JsonObject} / {@link Json} 模型，无任何新增依赖。</p>
 *
 * <p>类型映射：</p>
 * <ul>
 *   <li>String / char / Character → string；</li>
 *   <li>byte/short/int/long 及其包装类型 → integer；</li>
 *   <li>float/double/BigDecimal/BigInteger → number；</li>
 *   <li>boolean/Boolean → boolean；</li>
 *   <li>枚举 → string + enum 常量名数组；</li>
 *   <li>List/Set/Collection/数组 → array + items；</li>
 *   <li>Map → object + additionalProperties（值类型的 schema）；</li>
 *   <li>普通 POJO / record → object + properties + required；</li>
 *   <li>Instant/LocalDateTime/Date → string(format=date-time)，LocalDate → string(format=date)；</li>
 *   <li>UUID → string(format=uuid)，URI/URL → string(format=uri)。</li>
 * </ul>
 *
 * <p><b>required 策略</b>：未引入外部注解依赖，故约定——<b>原始类型（unboxed）字段默认必填</b>，
 * 包装类型、String、集合、Map 等引用类型字段默认可选，不列入 required。</p>
 *
 * <p><b>循环引用</b>：递归过程中维护一份“正在访问的类”祖先链（enter 时入栈、leave 时出栈）；
 * 当再次遇到祖先链中的类时，终止递归并返回 {@code {"type":"object"}} 空对象 schema，避免栈溢出。</p>
 *
 * <p>本类无任何可变静态状态，所有方法均为静态且无状态，线程安全。</p>
 *
 * @author sureai
 * @since 1.5.0
 */
public final class JsonSchemaGenerator {

	/** JSON Schema 规范版本标识，仅出现在根 schema 上。 */
	private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

	/** 私有构造器，工具类禁止实例化。 */
	private JsonSchemaGenerator() {
		throw new AssertionError("No instances");
	}

	/**
	 * 生成指定类的 JSON Schema，返回 core 已有的 {@link JsonObject}。
	 *
	 * <p>根 schema 会附带 {@code $schema} 声明；嵌套节点不带。</p>
	 *
	 * @param clazz 目标类（POJO / record / 任意受支持类型）
	 * @return 根 schema 对象
	 */
	public static JsonObject generate(Class<?> clazz) {
		JsonObject schema = build(clazz, clazz, new HashSet<>());
		schema.put("$schema", DRAFT_2020_12);
		return schema;
	}

	/**
	 * 生成指定类的 JSON Schema，返回紧凑 JSON 字符串。
	 *
	 * @param clazz 目标类
	 * @return 合法 JSON 字符串
	 */
	public static String generateString(Class<?> clazz) {
		return Json.stringify(generate(clazz));
	}

	/**
	 * 生成指定类的 JSON Schema，返回带缩进美化的 JSON 字符串。
	 *
	 * @param clazz 目标类
	 * @return 美化后的合法 JSON 字符串
	 */
	public static String generateStringPretty(Class<?> clazz) {
		return pretty(generate(clazz), 0);
	}

	// ==================== 核心递归 ====================

	/**
	 * 递归构建某个类型节点的 schema。
	 *
	 * @param raw      擦除后的原始类型
	 * @param generic  泛型类型（用于集合元素 / Map 值类型解析），可能等于 raw
	 * @param visiting 当前递归祖先类链，用于循环引用检测
	 * @return 该类型对应的 schema 对象
	 */
	private static JsonObject build(Class<?> raw, Type generic, Set<Class<?>> visiting) {
		// 原始类型
		if (raw.isPrimitive()) {
			return primitiveSchema(raw);
		}
		// 字符串
		if (raw == String.class || raw == Character.class) {
			return stringSchema(null);
		}
		// 布尔
		if (raw == Boolean.class) {
			return typeOnly("boolean");
		}
		// 整数
		if (isIntegerType(raw)) {
			return typeOnly("integer");
		}
		// 浮点数
		if (isNumberType(raw)) {
			return typeOnly("number");
		}
		// 枚举
		if (raw.isEnum()) {
			return enumSchema(raw);
		}
		// 时间类型
		if (raw == Instant.class || raw == LocalDateTime.class || raw == Date.class) {
			return stringSchema("date-time");
		}
		if (raw == LocalDate.class) {
			return stringSchema("date");
		}
		// 标识 / 定位
		if (raw == UUID.class) {
			return stringSchema("uuid");
		}
		if (raw == URI.class || raw == URL.class) {
			return stringSchema("uri");
		}
		// 数组
		if (raw.isArray()) {
			JsonObject schema = typeOnly("array");
			schema.set("items", build(raw.getComponentType(), raw.getComponentType(), visiting));
			return schema;
		}
		// 集合
		if (Collection.class.isAssignableFrom(raw)) {
			Type elementType = firstTypeArgument(generic);
			JsonObject schema = typeOnly("array");
			schema.set("items", build(toRawClass(elementType), elementType, visiting));
			return schema;
		}
		// Map
		if (Map.class.isAssignableFrom(raw)) {
			Type valueType = secondTypeArgument(generic);
			JsonObject schema = typeOnly("object");
			schema.set("additionalProperties", build(toRawClass(valueType), valueType, visiting));
			return schema;
		}
		// 普通 POJO / record
		return objectSchema(raw, visiting);
	}

	/** 构建对象（POJO / record）schema：properties + required。 */
	private static JsonObject objectSchema(Class<?> raw, Set<Class<?>> visiting) {
		JsonObject schema = typeOnly("object");
		// 循环引用命中祖先链：终止递归，返回空对象 schema
		if (visiting.contains(raw)) {
			return schema;
		}
		visiting.add(raw);
		try {
			JsonObject properties = Json.object();
			JsonArray required = Json.array();
			if (raw.isRecord()) {
				RecordComponent[] components = raw.getRecordComponents();
				for (RecordComponent component : components) {
					String name = component.getName();
					properties.put(name, build(component.getType(), component.getGenericType(), visiting));
					if (component.getType().isPrimitive()) {
						required.add(name);
					}
				}
			} else {
				List<Field> fields = collectFields(raw);
				for (Field field : fields) {
					String name = field.getName();
					properties.put(name, build(field.getType(), field.getGenericType(), visiting));
					if (field.getType().isPrimitive()) {
						required.add(name);
					}
				}
			}
			if (properties.size() > 0) {
				schema.set("properties", properties);
			}
			if (required.size() > 0) {
				schema.set("required", required);
			}
		} finally {
			visiting.remove(raw);
		}
		return schema;
	}

	// ==================== 节点构造辅助 ====================

	/** 仅含 type 的最简 schema。 */
	private static JsonObject typeOnly(String type) {
		JsonObject schema = Json.object();
		schema.put("type", type);
		return schema;
	}

	/** type=string，可选 format。 */
	private static JsonObject stringSchema(String format) {
		JsonObject schema = typeOnly("string");
		if (format != null) {
			schema.put("format", format);
		}
		return schema;
	}

	/** 原始类型到 schema 的映射（char 归为 string）。 */
	private static JsonObject primitiveSchema(Class<?> raw) {
		String name = raw.getName();
		switch (name) {
			case "char":
				return stringSchema(null);
			case "float":
			case "double":
				return typeOnly("number");
			case "boolean":
				return typeOnly("boolean");
			default:
				// byte/short/int/long
				return typeOnly("integer");
		}
	}

	/** 枚举：type=string + enum 常量名数组。 */
	private static JsonObject enumSchema(Class<?> raw) {
		JsonObject schema = typeOnly("string");
		JsonArray values = Json.array();
		Object[] constants = raw.getEnumConstants();
		if (constants != null) {
			for (Object constant : constants) {
				values.add(((Enum<?>) constant).name());
			}
		}
		schema.set("enum", values);
		return schema;
	}

	// ==================== 类型判定与解析 ====================

	/** 是否为整数类型（含包装类）。 */
	private static boolean isIntegerType(Class<?> raw) {
		return raw == Integer.class || raw == Long.class || raw == Short.class || raw == Byte.class;
	}

	/** 是否为浮点类型（含 BigDecimal/BigInteger）。 */
	private static boolean isNumberType(Class<?> raw) {
		return raw == Float.class || raw == Double.class || raw == BigDecimal.class || raw == BigInteger.class;
	}

	/** 取参数化类型的第一个实际类型参数（集合元素），无法解析时退化为 Object。 */
	private static Type firstTypeArgument(Type generic) {
		if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
			return pt.getActualTypeArguments()[0];
		}
		return Object.class;
	}

	/** 取参数化类型的第二个实际类型参数（Map 值），无法解析时退化为 Object。 */
	private static Type secondTypeArgument(Type generic) {
		if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 1) {
			return pt.getActualTypeArguments()[1];
		}
		return Object.class;
	}

	/** 将 Type 归一为原始 Class；通配符 / 类型变量等无法解析时退化为 Object。 */
	private static Class<?> toRawClass(Type type) {
		if (type instanceof Class<?> c) {
			return c;
		}
		if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
			return c;
		}
		return Object.class;
	}

	/**
	 * 收集 POJO 的所有实例字段：沿父类链向上，跳过 static / transient / synthetic 字段，
	 * 子类字段优先（同名遮蔽保留子类版本）。
	 */
	private static List<Field> collectFields(Class<?> clazz) {
		List<Field> fields = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		Class<?> current = clazz;
		while (current != null && current != Object.class) {
			for (Field field : current.getDeclaredFields()) {
				int modifiers = field.getModifiers();
				if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
					continue;
				}
				if (seen.add(field.getName())) {
					fields.add(field);
				}
			}
			current = current.getSuperclass();
		}
		return fields;
	}

	// ==================== 美化序列化 ====================

	/** 递归生成带缩进的 JSON 文本（叶子节点直接复用 core 的转义序列化）。 */
	private static String pretty(JsonElement element, int depth) {
		if (element == null || element.isNull()) {
			return "null";
		}
		if (element.isObject()) {
			JsonObject obj = element.getAsJsonObject();
			if (obj.size() == 0) {
				return "{}";
			}
			StringBuilder sb = new StringBuilder("{\n");
			boolean first = true;
			for (Map.Entry<String, JsonElement> entry : obj.entries()) {
				if (!first) {
					sb.append(",\n");
				}
				first = false;
				sb.append(indent(depth + 1))
					.append(Json.stringify(entry.getKey()))
					.append(": ")
					.append(pretty(entry.getValue(), depth + 1));
			}
			sb.append('\n').append(indent(depth)).append('}');
			return sb.toString();
		}
		if (element.isArray()) {
			JsonArray arr = element.getAsJsonArray();
			if (arr.size() == 0) {
				return "[]";
			}
			StringBuilder sb = new StringBuilder("[\n");
			for (int i = 0; i < arr.size(); i++) {
				if (i > 0) {
					sb.append(",\n");
				}
				sb.append(indent(depth + 1)).append(pretty(arr.get(i), depth + 1));
			}
			sb.append('\n').append(indent(depth)).append(']');
			return sb.toString();
		}
		// 原始值：字符串/数字/布尔，复用 core 的正确转义
		return Json.stringify(element);
	}

	/** 生成一层缩进（两个空格）。 */
	private static String indent(int depth) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++) {
			sb.append("  ");
		}
		return sb.toString();
	}
}
