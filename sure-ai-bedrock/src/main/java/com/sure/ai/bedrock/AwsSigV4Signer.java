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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sure.ai.exception.AiException;

/**
 * AWS Signature Version 4（SigV4）签名器，纯 JDK 实现，零第三方依赖。
 *
 * <p>实现 AWS 官方签名流程（参见
 * <a href="https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html">
 * Creating a signed AWS API request</a>），使用 {@code javax.crypto.Mac}（HmacSHA256）与
 * {@code java.security.MessageDigest}（SHA-256），不引入任何 AWS SDK。</p>
 *
 * <p>签名分三步：</p>
 * <ol>
 *   <li><b>构造规范请求（Canonical Request）</b>：HTTP 方法、规范 URI、规范查询串、
 *       CanonicalHeaders（{@code host}、{@code x-amz-date}、{@code x-amz-content-sha256}，
 *       有临时凭证时追加 {@code x-amz-security-token}）、SignedHeaders、以及
 *       {@code Hex(SHA-256(payload))}，各部分以 {@code \n} 连接。</li>
 *   <li><b>构造待签字符串（String to Sign）</b>：
 *       {@code "AWS4-HMAC-SHA256" \n UTC时间戳 \n credentialScope \n Hex(SHA-256(规范请求))}，
 *       其中 {@code credentialScope = date/region/service/aws4_request}。</li>
 *   <li><b>派生签名密钥并计算签名</b>：链式 HMAC-SHA256：
 *       {@code ("AWS4"+secretKey) -> date -> region -> service -> "aws4_request"}，
 *       最终对 String to Sign 做 HMAC-SHA256 取十六进制。</li>
 * </ol>
 *
 * <p>最终拼装 {@code Authorization} 头：
 * {@code AWS4-HMAC-SHA256 Credential=<ak>/<scope>, SignedHeaders=<h1;h2>, Signature=<hex>}。</p>
 *
 * <p>本类不可变、线程安全：同一签名器实例可被多线程并发用于不同请求签名。</p>
 *
 * @author sureai
 * @since 1.2.0
 */
public final class AwsSigV4Signer {

	/** 签名算法标识。 */
	public static final String ALGORITHM = "AWS4-HMAC-SHA256";

	/** 签名密钥链末端常量。 */
	private static final String AWS4_REQUEST = "aws4_request";

	/** x-amz-date 格式：yyyyMMdd'T'HHmmss'Z'（UTC，ISO 8601 基本格式）。 */
	private static final DateTimeFormatter AMZ_DATE =
		DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

	/** 短日期格式：yyyyMMdd。 */
	private static final DateTimeFormatter DATE_STAMP =
		DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

	/** Access Key ID。 */
	private final String accessKey;

	/** Secret Access Key。 */
	private final String secretKey;

	/** 临时会话令牌（可选，为空则不加 x-amz-security-token）。 */
	private final String sessionToken;

	/** AWS 区域，如 us-east-1。 */
	private final String region;

	/** 服务名，bedrock 调用为 {@code "bedrock"}。 */
	private final String service;

	/**
	 * 构造签名器。
	 *
	 * @param accessKey    AWS Access Key ID（必填）
	 * @param secretKey     AWS Secret Access Key（必填）
	 * @param sessionToken  临时会话令牌（可选，可为 null）
	 * @param region       AWS 区域（必填，如 us-east-1）
	 * @param service       服务名（必填，如 bedrock）
	 * @throws AiException 必填参数为空时抛出
	 */
	public AwsSigV4Signer(String accessKey, String secretKey, String sessionToken,
			String region, String service) {
		if (accessKey == null || accessKey.isBlank()) {
			throw new AiException("AWS access key must not be blank");
		}
		if (secretKey == null || secretKey.isBlank()) {
			throw new AiException("AWS secret key must not be blank");
		}
		if (region == null || region.isBlank()) {
			throw new AiException("AWS region must not be blank");
		}
		if (service == null || service.isBlank()) {
			throw new AiException("AWS signing service must not be blank");
		}
		this.accessKey = accessKey;
		this.secretKey = secretKey;
		this.sessionToken = (sessionToken == null || sessionToken.isBlank()) ? null : sessionToken;
		this.region = region;
		this.service = service;
	}

	/**
	 * 对一次 HTTP 请求计算全部签名相关请求头。
	 *
	 * <p>返回的 Map 包含：{@code Host}、{@code X-Amz-Date}、{@code X-Amz-Content-Sha256}、
	 * {@code Authorization}，以及（若存在临时令牌）{@code X-Amz-Security-Token}。</p>
	 *
	 * @param method        HTTP 方法（大写，如 POST）
	 * @param host          请求主机（不含协议，如 bedrock-runtime.us-east-1.amazonaws.com）
	 * @param canonicalUri  规范 URI（路径，如 /model/xxx/converse）
	 * @param canonicalQuery 规范查询串（无查询时传空串）
	 * @param payloadBody   请求体原文（用于计算 SHA-256）
	 * @param now           请求时间（UTC）
	 * @return 需设置到请求上的签名头（保留插入顺序）
	 */
	public Map<String, String> sign(String method, String host, String canonicalUri,
			String canonicalQuery, String payloadBody, ZonedDateTime now) {
		String amzDate = AMZ_DATE.format(now);
		String dateStamp = DATE_STAMP.format(now);
		byte[] payloadBytes = payloadBody.getBytes(StandardCharsets.UTF_8);
		String payloadHash = sha256Hex(payloadBytes);

		// 收集参与签名的头（小写名），按名称排序拼装 CanonicalHeaders 与 SignedHeaders
		Map<String, String> signed = new LinkedHashMap<>();
		signed.put("host", host);
		signed.put("x-amz-date", amzDate);
		signed.put("x-amz-content-sha256", payloadHash);
		if (this.sessionToken != null) {
			signed.put("x-amz-security-token", this.sessionToken);
		}
		List<String> names = new ArrayList<>(signed.keySet());
		names.sort(String::compareTo);

		StringBuilder canonicalHeaders = new StringBuilder();
		StringBuilder signedHeaders = new StringBuilder();
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			canonicalHeaders.append(name).append(':').append(signed.get(name)).append('\n');
			if (i > 0) {
				signedHeaders.append(';');
			}
			signedHeaders.append(name);
		}

		String canonicalRequest = method + '\n'
			+ canonicalUri + '\n'
			+ canonicalQuery + '\n'
			+ canonicalHeaders + '\n'
			+ signedHeaders + '\n'
			+ payloadHash;

		String scope = dateStamp + '/' + this.region + '/' + this.service + '/' + AWS4_REQUEST;
		String stringToSign = ALGORITHM + '\n'
			+ amzDate + '\n'
			+ scope + '\n'
			+ sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

		byte[] signingKey = deriveSigningKey(this.secretKey, dateStamp, this.region, this.service);
		String signature = HexFormat.of().formatHex(hmacSha256(signingKey,
			stringToSign.getBytes(StandardCharsets.UTF_8)));

		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Host", host);
		headers.put("X-Amz-Date", amzDate);
		headers.put("X-Amz-Content-Sha256", payloadHash);
		headers.put("Authorization", ALGORITHM
			+ " Credential=" + this.accessKey + '/' + scope
			+ ", SignedHeaders=" + signedHeaders
			+ ", Signature=" + signature);
		if (this.sessionToken != null) {
			headers.put("X-Amz-Security-Token", this.sessionToken);
		}
		return headers;
	}

	/**
	 * 链式派生 SigV4 签名密钥（kSigning）。
	 *
	 * <p>{@code kDate = HMAC("AWS4"+secretKey, dateStamp)}；逐级向下：
	 * {@code kRegion = HMAC(kDate, region)}、{@code kService = HMAC(kRegion, service)}、
	 * {@code kSigning = HMAC(kService, "aws4_request")}。</p>
	 *
	 * @param secretKey AWS Secret Access Key
	 * @param dateStamp 短日期（yyyyMMdd）
	 * @param region    区域
	 * @param service   服务名
	 * @return 派生签名密钥的原始字节
	 */
	static byte[] deriveSigningKey(String secretKey, String dateStamp, String region, String service) {
		byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8),
			dateStamp.getBytes(StandardCharsets.UTF_8));
		byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
		byte[] kService = hmacSha256(kRegion, service.getBytes(StandardCharsets.UTF_8));
		return hmacSha256(kService, AWS4_REQUEST.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * 计算 HMAC-SHA256。
	 *
	 * @param key 密钥
	 * @param data 数据
	 * @return 摘要字节
	 */
	static byte[] hmacSha256(byte[] key, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data);
		} catch (NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
			throw new AiException("SigV4 HMAC-SHA256 failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * 计算 SHA-256 并返回小写十六进制。
	 *
	 * @param data 数据
	 * @return 小写十六进制摘要
	 */
	static String sha256Hex(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(data));
		} catch (NoSuchAlgorithmException ex) {
			throw new AiException("SigV4 SHA-256 failed: " + ex.getMessage(), ex);
		}
	}
}
