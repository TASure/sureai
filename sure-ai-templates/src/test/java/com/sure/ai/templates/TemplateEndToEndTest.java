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

package com.sure.ai.templates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 模板生成 → 编译 → 测试 闭环集成测试（零真实网络）。
 *
 * <p>前置：本地仓库需有当前版本（{@code sure-ai-all:VERSION}）制品；
 * 缺失时先在本工程根执行一次 {@code mvn -pl sure-ai-all -am install -DskipTests}。
 * 若 Maven 不可用则跳过（assume），避免 CI 假红。</p>
 */
public class TemplateEndToEndTest {

	/** 生成工程内嵌依赖的版本（与生成器缺省一致）。 */
	private static final String VERSION = TemplateGenerator.DEFAULT_SUREAI_VERSION;

	/** Maven 调用超时（含首次依赖解析与编译；-am 冷启动构建 40+ 模块需充分余量）。 */
	private static final long MVN_TIMEOUT_SECONDS = 600;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/**
	 * 跨平台 Maven 命令：Windows 下 Maven 仅有 {@code mvn.cmd}（无 {@code mvn.exe}），
	 * ProcessBuilder 不能直接执行 {@code .cmd}，须经 {@code cmd /c} 包装；Unix 系直接 {@code mvn}。
	 */
	private static List<String> mvn(String... args) {
		List<String> cmd = new ArrayList<>();
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			cmd.add("cmd");
			cmd.add("/c");
			cmd.add("mvn.cmd");
		} else {
			cmd.add("mvn");
		}
		cmd.addAll(Arrays.asList(args));
		return cmd;
	}

	@BeforeClass
	public static void ensureLocalArtifacts() throws Exception {
		Path all = Path.of(System.getProperty("user.home"),
			".m2/repository/io/github/tasure/sure-ai-all/" + VERSION);
		Path bom = Path.of(System.getProperty("user.home"),
			".m2/repository/io/github/tasure/sure-ai-bom/" + VERSION);
		if (Files.isDirectory(all) && Files.isDirectory(bom)) {
			return;
		}
		// 本地仓库缺当前版本：先装 bom 与库链制品（一次，供模板工程解析）。
		// 注意：surefire 下 user.dir 为模块目录，必须上溯到含 <artifactId>sureai</artifactId> 的工程根，
		// 否则 -pl sure-ai-bom,sure-ai-all 找不到模块。
		Path root = findProjectRoot();
		Process p = new ProcessBuilder(mvn("-q", "-B", "install",
			"-DskipTests", "-Dgpg.skip=true", "-pl", "sure-ai-bom,sure-ai-all", "-am"))
			.directory(root.toFile())
			.inheritIO()
			.start();
		if (!p.waitFor(MVN_TIMEOUT_SECONDS, TimeUnit.SECONDS) || p.exitValue() != 0) {
			fail("前置 install 失败（exit=" + p.exitValue() + "），模板闭环测试无法解析 sure-ai-all:"
				+ VERSION + "；请先在工程根执行 mvn -pl sure-ai-bom,sure-ai-all -am install -DskipTests -Dgpg.skip=true");
		}
	}

	/** 从 user.dir 上溯，定位 sureai 工程根（根 pom 特征：含 {@code <modules>} 且自身 artifactId 为 {@code sureai}；模块 pom 的 parent 引用 artifactId 同为 sureai，需靠 modules 区分）。 */
	private static Path findProjectRoot() throws IOException {
		Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (dir != null) {
			Path pom = dir.resolve("pom.xml");
			if (Files.isRegularFile(pom)) {
				String text = Files.readString(pom, StandardCharsets.UTF_8);
				if (text.contains("<modules>") && text.contains("<artifactId>sureai</artifactId>")) {
					return dir;
				}
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("未找到 sureai 工程根（pom.xml 含 <modules> 且 artifactId 为 sureai）");
	}

	@Test
	public void ragKbGeneratedProjectBuildsAndTests() throws Exception {
		assertGeneratedProjectBuilds("rag-kb", "My KB");
	}

	@Test
	public void customerServiceGeneratedProjectBuildsAndTests() throws Exception {
		assertGeneratedProjectBuilds("customer-service", "Support Bot");
	}

	@Test
	public void codeAssistantGeneratedProjectBuildsAndTests() throws Exception {
		assertGeneratedProjectBuilds("code-assistant", "Code Helper");
	}

	private void assertGeneratedProjectBuilds(String template, String projectName) throws Exception {
		Path target = tmp.newFolder(template + "-out").toPath();
		List<Path> generated = TemplateGenerator.generate(template, target,
			Map.of("projectName", projectName));
		assertTrue("生成文件数应 ≥4（pom/README/gitignore/源文件）", generated.size() >= 4);

		Process p = new ProcessBuilder(mvn("-q", "-B", "-f", target.toString(), "test"))
			.directory(target.toFile())
			.inheritIO()
			.start();
		if (!p.waitFor(MVN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			fail(template + "：mvn test 超时（" + MVN_TIMEOUT_SECONDS + "s）");
		}
		assertEquals(template + "：生成工程 mvn test 失败", 0, p.exitValue());
		assertTrue("pom 中 sure-ai-all 版本应为 " + VERSION,
			Files.readString(target.resolve("pom.xml"), StandardCharsets.UTF_8)
				.contains("<version>" + VERSION + "</version>"));
	}
}
