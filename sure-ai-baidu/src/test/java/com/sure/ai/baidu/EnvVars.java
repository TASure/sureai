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

package com.sure.ai.baidu;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试辅助：经反射读写 JDK 进程环境变量表（{@code ProcessEnvironment.theEnvironment}）。
 *
 * <p>仅用于覆盖各平台 {@code XxxUtil.buildConfigFromEnv}/{@code buildSpeechConfig} 等
 * 环境变量分支；调用方须在 {@code finally} 中用 {@link #restore()} 还原，避免污染同 JVM
 * 内其它测试。需要 surefire 追加 {@code --add-opens java.base/java.lang=ALL-UNNAMED}。</p>
 *
 * @author sureai
 */
final class EnvVars {

	/** 修改前的原始环境变量快照，用于还原。 */
	private final Map<String, String> snapshot = new HashMap<>();

	/** 不可实例化。 */
	private EnvVars() {
	}

	/**
	 * 开启会话：快照当前相关环境变量。
	 *
	 * @return 会话实例
	 */
	static EnvVars begin() {
		return new EnvVars();
	}

	/**
	 * 设置环境变量为指定值（{@code null} 表示删除），同时记录旧值。
	 *
	 * @param key   变量名
	 * @param value 目标值，{@code null} 表示删除
	 */
	void set(String key, String value) {
		this.snapshot.putIfAbsent(key, System.getenv(key));
		Map<String, String> env = envMap();
		if (value == null) {
			env.remove(key);
		} else {
			env.put(key, value);
		}
		// 校验注入生效：个别平台（如 Windows）System.getenv 与反射表分离，注入不生效
		// 时标记测试跳过，避免误报 ERROR/FAILURE
		String actual = System.getenv(key);
		if (!java.util.Objects.equals(actual, value)) {
			throw new org.junit.AssumptionViolatedException(
				"环境变量注入未生效（" + key + "），当前平台跳过测试");
		}
	}

	/** 还原到 {@link #begin()} 时的环境变量快照。 */
	void restore() {
		Map<String, String> env = envMap();
		for (Map.Entry<String, String> e : this.snapshot.entrySet()) {
			String v = e.getValue();
			if (v == null) {
				env.remove(e.getKey());
			} else {
				env.put(e.getKey(), v);
			}
		}
		this.snapshot.clear();
	}

	/** 反射取得可变环境变量底层 Map（JDK21 中 System.getenv 读 theUnmodifiableEnvironment 委托）。 */
	@SuppressWarnings("unchecked")
	private static Map<String, String> envMap() {
		try {
			Class<?> clazz = Class.forName("java.lang.ProcessEnvironment");
			Field f = clazz.getDeclaredField("theUnmodifiableEnvironment");
			f.setAccessible(true);
			Object unmod = f.get(null);
			Field m = unmod.getClass().getDeclaredField("m");
			m.setAccessible(true);
			return (Map<String, String>) m.get(unmod);
		} catch (ReflectiveOperationException ex) {
			// 环境变量反射在当前平台不可用（如个别 Windows/JDK 组合）→ 测试标记跳过而非失败
			throw new org.junit.AssumptionViolatedException(
				"环境变量反射在当前平台不可用，跳过测试（" + ex.getMessage() + "）");
		}
	}
}
