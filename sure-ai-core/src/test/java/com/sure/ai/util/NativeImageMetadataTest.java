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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

import com.sure.ai.internal.json.Json;
import com.sure.ai.internal.json.JsonArray;
import com.sure.ai.internal.json.JsonObject;

/**
 * GraalVM native-image AOT 元数据完整性守卫测试。
 *
 * <p>背景：{@link JsonMapper} 与 {@link JsonSchemaGenerator} 依赖
 * {@code Class.getRecordComponents()} + 规范构造器反射 + 访问器反射来完成 record 的
 * 序列化/反序列化与 JSON Schema 生成；native-image 下这些反射目标必须在
 * {@code META-INF/native-image/.../reflect-config.json} 中预注册，否则运行期抛
 * {@link ClassNotFoundException}（反射对象未被 native 编译保留）。</p>
 *
 * <p>本测试不编译 native 镜像（CI 沙箱通常无 GraalVM），而是在 JVM 下做静态守卫：
 * <ol>
 *   <li>reflect-config.json 存在且可用自研 JSON 解析器解析；</li>
 *   <li>{@code com.sure.ai.model} 包下<b>全部</b> record 类（含嵌套 record）都在注册清单里——
 *       新增 model record 时若忘补元数据，本测试立即红；</li>
 *   <li>清单里每个名字都真实存在、确为 record、且四项反射开关齐全；</li>
 *   <li>native-image.properties 含 HttpClient/SSE 所需的编译参数；</li>
 *   <li>resource-config.json 存在且可解析。</li>
 * </ol>
 *
 * @author sureai
 * @since 2.0.0
 */
public class NativeImageMetadataTest {

	/** core 模块元数据目录（随 jar 打包后位于 classpath 根）。 */
	private static final String META_DIR =
		"/META-INF/native-image/io.github.tasure/sure-ai-core/";

	/**
	 * reflect-config.json 存在且可被自研 JSON 解析器解析为数组。
	 */
	@Test
	public void reflectConfigExistsAndParses() {
		String text = readRequired(META_DIR + "reflect-config.json");
		JsonArray arr = Json.parse(text).getAsJsonArray();
		assertTrue("reflect-config.json 不应为空", arr.size() > 0);
	}

	/**
	 * com.sure.ai.model 包下全部 record（含嵌套 record）均已在 reflect-config 注册。
	 */
	@Test
	public void allModelRecordsAreRegistered() throws Exception {
		Set<String> configured = configuredRecordNames();
		Set<String> discovered = discoverModelRecords();
		assertTrue("应至少发现 1 个 model record（防止 classpath 扫描失效导致假绿）",
			discovered.size() >= 20);
		for (String recordName : discovered) {
			assertTrue("model record 未在 reflect-config.json 注册: " + recordName,
				configured.contains(recordName));
		}
	}

	/**
	 * reflect-config 中每个条目：类真实存在、确为 record、四项反射开关全开。
	 */
	@Test
	public void everyEntryIsARealRecordWithFullReflectionFlags() {
		JsonArray arr = Json.parse(readRequired(META_DIR + "reflect-config.json"))
			.getAsJsonArray();
		for (int i = 0; i < arr.size(); i++) {
			JsonObject entry = arr.getJsonObject(i);
			String name = entry.getString("name");
			Class<?> clazz;
			try {
				clazz = Class.forName(name);
			} catch (ClassNotFoundException ex) {
				fail("reflect-config.json 条目指向不存在的类: " + name);
				return;
			}
			assertTrue("reflect-config.json 注册了非 record 类: " + name, clazz.isRecord());
			assertTrue(name + " 缺少 allDeclaredConstructors",
				entry.get("allDeclaredConstructors").getAsBoolean());
			assertTrue(name + " 缺少 allDeclaredMethods",
				entry.get("allDeclaredMethods").getAsBoolean());
			assertTrue(name + " 缺少 allDeclaredFields",
				entry.get("allDeclaredFields").getAsBoolean());
			assertTrue(name + " 缺少 allRecordComponents",
				entry.get("allRecordComponents").getAsBoolean());
		}
	}

	/**
	 * native-image.properties 存在并含 HttpClient/SSE 必需编译参数。
	 */
	@Test
	public void nativeImagePropertiesCarryRequiredArgs() {
		String props = readRequired(META_DIR + "native-image.properties");
		assertTrue("缺少 -H:+AddAllCharsets（SSE/HTTP 字符集）",
			props.contains("-H:+AddAllCharsets"));
		assertTrue("缺少 --enable-url-protocols（JDK HttpClient https/http）",
			props.contains("--enable-url-protocols=https,http"));
		assertTrue("缺少 Args 行声明", props.contains("Args"));
	}

	/**
	 * resource-config.json 存在且可解析（core 当前无内置 classpath 资源，显式空配置）。
	 */
	@Test
	public void resourceConfigExistsAndParses() {
		String text = readRequired(META_DIR + "resource-config.json");
		JsonObject root = Json.parse(text).getAsJsonObject();
		assertTrue("resource-config.json 应声明 resources 节点", root.has("resources"));
	}

	// ==================== 辅助 ====================

	/** 读取 classpath 资源文本，缺失直接失败。 */
	private static String readRequired(String path) {
		try (InputStream in = NativeImageMetadataTest.class.getResourceAsStream(path)) {
			assertNotNull("缺少 native-image 元数据文件: " + path, in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception ex) {
			throw new AssertionError("读取元数据文件失败: " + path, ex);
		}
	}

	/** 解析 reflect-config.json 中全部 record 类名。 */
	private static Set<String> configuredRecordNames() {
		JsonArray arr = Json.parse(readRequired(META_DIR + "reflect-config.json"))
			.getAsJsonArray();
		Set<String> names = new LinkedHashSet<>();
		for (int i = 0; i < arr.size(); i++) {
			names.add(arr.getJsonObject(i).getString("name"));
		}
		return names;
	}

	/**
	 * 从 classpath（target/classes 展开目录）枚举 com.sure.ai.model 包下全部 record 类
	 * （含嵌套 record，文件名形如 BatchResponse$RequestCounts.class）。
	 */
	private static Set<String> discoverModelRecords() throws Exception {
		Class<?> anchor = Class.forName("com.sure.ai.model.ChatResponse");
		File codeSource = new File(anchor.getProtectionDomain()
			.getCodeSource().getLocation().toURI());
		File modelDir = new File(codeSource, "com/sure/ai/model");
		Set<String> records = new LinkedHashSet<>();
		File[] classFiles = modelDir.listFiles(f -> f.getName().endsWith(".class"));
		assertNotNull("无法在 classpath 定位 com/sure/ai/model 目录: " + modelDir, classFiles);
		for (File f : classFiles) {
			String fqn = "com.sure.ai.model."
				+ f.getName().substring(0, f.getName().length() - ".class".length());
			Class<?> clazz = Class.forName(fqn);
			if (clazz.isRecord()) {
				records.add(fqn);
			}
		}
		return records;
	}

}
