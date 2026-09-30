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

package com.sure.ai.archetype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * archetype 模板静态断言：零网络、零 Maven 调用，直接校验 classpath 上的
 * archetype-metadata.xml 与模板文件内容。
 *
 * @author sureai
 * @since 2.0.0
 */
public class ArchetypeTemplateTest {

	private String metadata;
	private String templatePom;
	private String mainTemplate;
	private String readme;

	/** 载入模板资源到内存字符串。 */
	@Before
	public void setUp() throws Exception {
		metadata = readResource("/META-INF/maven/archetype-metadata.xml");
		templatePom = readResource("/archetype-resources/pom.xml");
		mainTemplate = readResource("/archetype-resources/src/main/java/Main.java");
		readme = readResource("/archetype-resources/README.md");
	}

	@Test
	public void metadataParsesAndDeclaresCustomProperties() throws Exception {
		Document doc = parse(metadata);
		assertEquals("archetype-descriptor", doc.getDocumentElement().getLocalName());

		NodeList props = doc.getElementsByTagName("requiredProperty");
		boolean hasPlatform = false;
		boolean hasSureAiVersion = false;
		for (int i = 0; i < props.getLength(); i++) {
			Element el = (Element) props.item(i);
			String key = el.getAttribute("key");
			if ("platform".equals(key)) {
				hasPlatform = true;
				assertEquals("openai", el.getElementsByTagName("defaultValue").item(0).getTextContent().trim());
			}
			if ("sureaiVersion".equals(key)) {
				hasSureAiVersion = true;
			}
		}
		assertTrue("metadata 必须声明 platform 属性", hasPlatform);
		assertTrue("metadata 必须声明 sureaiVersion 属性", hasSureAiVersion);
	}

	@Test
	public void metadataDeclaresPackagedJavaFileSet() throws Exception {
		Document doc = parse(metadata);
		NodeList sets = doc.getElementsByTagName("fileSet");
		boolean packagedJava = false;
		boolean rootFiltered = false;
		for (int i = 0; i < sets.getLength(); i++) {
			Element el = (Element) sets.item(i);
			String dir = el.getElementsByTagName("directory").item(0).getTextContent().trim();
			boolean packaged = "true".equals(el.getAttribute("packaged"));
			if ("src/main/java".equals(dir) && packaged) {
				packagedJava = true;
			}
			// 根文件集 directory 必须留空（maven-archetype-plugin 3.2.1 在 directory="/" 时
			// 会把 basedir 拼成 "//"，导致裸文件名 README/.gitignore 匹配不到）
			if ("".equals(dir)) {
				rootFiltered = true;
			}
		}
		assertTrue("必须有 packaged 的 src/main/java 文件集", packagedJava);
		assertTrue("必须有根目录文件集", rootFiltered);
	}

	@Test
	public void templatePomImportsBomAndSinglePlatform() {
		assertTrue("模板 pom 必须 import sure-ai-bom", templatePom.contains("sure-ai-bom"));
		assertTrue("模板 pom 必须使用 import scope", templatePom.contains("<scope>import</scope>"));
		assertTrue("模板 pom 依赖必须按平台变量生成 sure-ai-${platform}",
			templatePom.contains("sure-ai-${platform}"));
		assertTrue("模板 pom 必须 release 21", templatePom.contains("<maven.compiler.release>21</maven.compiler.release>"));
	}

	@Test
	public void mainTemplateUsesPackageVarAndOpenAiBranch() {
		assertTrue("Main 模板必须声明 package ${package}", mainTemplate.contains("package ${package};"));
		assertTrue("Main 模板必须含 openai 客户端分支", mainTemplate.contains("new OpenAiClient(config)"));
		assertTrue("Main 模板必须按平台生成环境变量名",
			mainTemplate.contains("SURE_AI_${platform.toUpperCase()}_API_KEY"));
		assertTrue("Main 模板必须有 velocity 结束标记", mainTemplate.contains("#end"));
	}

	@Test
	public void readmeAndGitIgnorePresent() throws Exception {
		assertTrue("README 必须含工程变量名", readme.contains("${artifactId}"));
		String gitignore = readResource("/archetype-resources/.gitignore");
		assertTrue(".gitignore 必须忽略 target/", gitignore.contains("target/"));
	}

	private Document parse(String xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		assertNotNull(doc);
		return doc;
	}

	private String readResource(String path) throws Exception {
		try (InputStream in = getClass().getResourceAsStream(path)) {
			assertNotNull("缺少资源: " + path, in);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
			return out.toString(StandardCharsets.UTF_8.name());
		}
	}
}
