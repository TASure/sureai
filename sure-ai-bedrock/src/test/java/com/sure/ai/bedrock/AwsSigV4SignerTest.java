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

package com.sure.ai.bedrock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Map;

import org.junit.Test;

import com.sure.ai.exception.AiException;

/**
 * {@link AwsSigV4Signer} 单元测试：使用 AWS 官方测试向量。
 *
 * <p>官方向量（20150830 / us-east-1 / iam）派生 kSigning 期望值
 * c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public class AwsSigV4SignerTest {

	/** 官方测试向量：示例 Secret Key。 */
	private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

	/** 官方向量：派生 kSigning 期望值。 */
	private static final String EXPECTED_K_SIGNING =
		"c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9";

	/**
	 * 链式派生 kSigning 与官方 iam 向量一致。
	 */
	@Test
	public void testDeriveSigningKeyIamVector() {
		byte[] kSigning = AwsSigV4Signer.deriveSigningKey(SECRET, "20150830", "us-east-1", "iam");
		assertEquals(EXPECTED_K_SIGNING, HexFormat.of().formatHex(kSigning));
	}

	/**
	 * 完整签名：固定时间戳，校验 Credential Scope、SignedHeaders 与签名格式。
	 */
	@Test
	public void testSignBedrockPost() {
		AwsSigV4Signer signer = new AwsSigV4Signer("AKIDEXAMPLE", SECRET, null,
			"us-east-1", "bedrock");
		ZonedDateTime now = ZonedDateTime.of(2026, 9, 25, 12, 0, 0, 0, ZoneOffset.UTC);
		String payload = "{\"messages\":[]}";
		Map<String, String> headers = signer.sign("POST",
			"bedrock-runtime.us-east-1.amazonaws.com",
			"/model/anthropic.claude-3-5-sonnet-20240620-v1:0/converse",
			"", payload, now);

		assertEquals("20260925T120000Z", headers.get("X-Amz-Date"));
		assertEquals(AwsSigV4Signer.sha256Hex(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
			headers.get("X-Amz-Content-Sha256"));

		String auth = headers.get("Authorization");
		assertNotNull(auth);
		assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260925/us-east-1/bedrock/aws4_request, "
			+ "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature="));
		String sig = auth.substring(auth.lastIndexOf('=') + 1);
		assertEquals(64, sig.length());
		assertTrue(sig.matches("[0-9a-f]{64}"));
	}

	/**
	 * 带会话令牌时追加 x-amz-security-token 并纳入 SignedHeaders。
	 */
	@Test
	public void testSignWithSessionToken() {
		AwsSigV4Signer signer = new AwsSigV4Signer("AKID", SECRET, "TOKEN123",
			"us-east-1", "bedrock");
		ZonedDateTime now = ZonedDateTime.of(2026, 9, 25, 12, 0, 0, 0, ZoneOffset.UTC);
		Map<String, String> headers = signer.sign("POST", "h.example.com", "/m", "", "body", now);
		assertEquals("TOKEN123", headers.get("X-Amz-Security-Token"));
		String auth = headers.get("Authorization");
		assertTrue(auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token"));
	}

	/**
	 * 必填参数缺失抛 AiException。
	 */
	@Test
	public void testConstructorValidation() {
		assertThrows(AiException.class, () -> new AwsSigV4Signer(" ", SECRET, null, "us-east-1", "bedrock"));
		assertThrows(AiException.class, () -> new AwsSigV4Signer("AK", " ", null, "us-east-1", "bedrock"));
		assertThrows(AiException.class, () -> new AwsSigV4Signer("AK", SECRET, null, " ", "bedrock"));
		assertThrows(AiException.class, () -> new AwsSigV4Signer("AK", SECRET, null, "us-east-1", " "));
	}

	/**
	 * 空白 sessionToken 视为无令牌。
	 */
	@Test
	public void testBlankSessionTokenIgnored() {
		AwsSigV4Signer signer = new AwsSigV4Signer("AK", SECRET, "  ", "us-east-1", "bedrock");
		ZonedDateTime now = ZonedDateTime.of(2026, 9, 25, 12, 0, 0, 0, ZoneOffset.UTC);
		Map<String, String> headers = signer.sign("POST", "h.example.com", "/", "", "", now);
		assertNull(headers.get("X-Amz-Security-Token"));
	}
}
