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

package com.sure.ai.zhipu;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sure.ai.exception.AiException;
import com.sure.tool.codec.Base64Util;

/**
 * 智谱 JWT 签发器（包内私有）。
 *
 * <p>智谱开放平台的 apiKey 形如 {@code {id}.{secret}}，调用方需自行用 secret 作 HMAC-SHA256 密钥
 * 签发一个 HS256 JWT 作为 Bearer token，而不是直接把 apiKey 放进 Authorization。</p>
 *
 * <p>JWT 结构：</p>
 * <pre>
 *   header  = {"alg":"HS256","sign_type":"SIGN"}
 *   payload = {"api_key":"{id}","exp":{now+3600s},"timestamp":{now_ms}}
 * </pre>
 *
 * @author sureai
 * @since 0.1.0
 */
final class ZhipuJwtGenerator {

	/** JWT 有效期：1 小时（秒）。 */
	private static final long TTL_SECONDS = 3600L;

	/** 工具类禁止实例化。 */
	private ZhipuJwtGenerator() {
		throw new AssertionError("No instances");
	}

	/**
	 * 依据 apiKey（{@code id.secret} 格式）签发 Bearer JWT。
	 *
	 * @param apiKey 智谱 apiKey，格式 {@code {id}.{secret}}
	 * @return 形如 {@code Bearer &lt;jwt&gt;} 的完整鉴权串
	 */
	static String generate(String apiKey) {
		int idx = apiKey.indexOf('.');
		if (idx <= 0 || idx == apiKey.length() - 1) {
			throw new AiException("zhipu apiKey must be in format {id}.{secret}, got: " + apiKey);
		}
		String id = apiKey.substring(0, idx);
		String secret = apiKey.substring(idx + 1);

		long nowSec = Instant.now().getEpochSecond();
		long nowMs = System.currentTimeMillis();
		String header = "{\"alg\":\"HS256\",\"sign_type\":\"SIGN\"}";
		String payload = "{\"api_key\":\"" + id + "\",\"exp\":" + (nowSec + TTL_SECONDS)
			+ ",\"timestamp\":" + nowMs + "}";

		String h = Base64Util.encodeUrlSafe(header.getBytes(StandardCharsets.UTF_8));
		String p = Base64Util.encodeUrlSafe(payload.getBytes(StandardCharsets.UTF_8));
		String signingInput = h + "." + p;
		byte[] signature = hmacSha256(signingInput, secret.getBytes(StandardCharsets.UTF_8));
		String s = Base64Util.encodeUrlSafe(signature);
		return "Bearer " + signingInput + "." + s;
	}

	/** 计算 HMAC-SHA256 签名。 */
	private static byte[] hmacSha256(String data, byte[] key) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException ex) {
			throw new AiException("sign zhipu jwt failed: " + ex.getMessage(), ex);
		}
	}
}
