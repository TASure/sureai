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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 模板生成 → 编译 → 测试 闭环集成测试（零真实网络）。
 *
 * <p>前置：本地仓库需有 {@code sure-ai-all:2.10.0-SNAPSHOT} 制品；
 * 缺失时先在本工程根执行一次 {@code mvn -pl sure-ai-all -am install -DskipTests}。
 * 若 Maven 不可用则跳过（assume），避免 CI 假红。</p>
 */
public class TemplateEndToEndTest {

	/** 生成工程内嵌依赖的版本（与生成器缺省一致）。 */
	private static final String VERSION = TemplateGenerator.DEFAULT_SUREAI_VERSION;

	/** Maven 调用超时（含首次依赖解析与编译）。 */
	private static final long MVN_TIMEOUT_SECONDS = 300;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void ensureLocalArtifacts() throws Exception {
		Path all = Path.of(System.getProperty("user.home"),
			".m2/repository/io/github/tasure/sure-ai-all/" + VERSION);
		Path bom = Path.of(System.getProperty("user.home"),
			".m2/repository/io/github/tasure/sure-ai-bom/" + VERSION);
		if (Files.isDirectory(all) && Files.isDirectory(bom)) {
			return;
		}
		// 本地仓库缺 2.10.0-SNAPSHOT：先装 bom 与库链制品（一次，供模板工程解析）
		Path root = Path.of(System.getProperty("user.dir"));
		Process p = new ProcessBuilder("mvn", "-q", "-B", "install",
			"-DskipTests", "-Dgpg.skip=true", "-pl", "sure-ai-bom,sure-ai-all", "-am")
			.directory(root.toFile())
			.inheritIO()
			.start();
		if (!p.waitFor(MVN_TIMEOUT_SECONDS, TimeUnit.SECONDS) || p.exitValue() != 0) {
			fail("前置 install 失败（exit=" + p.exitValue() + "），模板闭环测试无法解析 sure-ai-all:"
				+ VERSION + "；请先在工程根执行 mvn -pl sure-ai-bom,sure-ai-all -am install -DskipTests -Dgpg.skip=true");
		}
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

		Process p = new ProcessBuilder("mvn", "-q", "-B", "-f", target.toString(), "test")
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
