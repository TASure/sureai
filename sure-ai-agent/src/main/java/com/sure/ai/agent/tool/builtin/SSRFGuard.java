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

package com.sure.ai.agent.tool.builtin;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 防护：校验目标 URI 的 host 解析后的 IP 是否为公网地址。
 *
 * <p>拒绝以下类型的地址：</p>
 * <ul>
 *   <li>回环地址（127.0.0.0/8, ::1）</li>
 *   <li>私有段（10/8, 172.16/12, 192.168/16, fc00::/7）</li>
 *   <li>链路本地（169.254/16, fe80::/10）</li>
 *   <li>任意本地地址（0.0.0.0, ::）</li>
 *   <li>多播地址（224/4, ff00::/8）</li>
 *   <li>IPv4-mapped IPv6 地址中嵌入的内网 IPv4（::ffff:127.0.0.1 等）</li>
 * </ul>
 *
 * <p><b>DNS rebinding 说明：</b>本类在请求前解析 host 并校验所有解析结果。
 * JDK HttpClient 发送请求时会再次解析 DNS，存在理论上的 TOCTOU（Time-of-check
 * -time-of-use）窗口——校验时解析到公网 IP，实际连接时解析到内网 IP。对于
 * HttpTool 这个内置工具而言，这是可接受的防护级别；严格防护需要自定义 DNS
 * 解析器或直接连接 IP 并手动管理 Host/SNI 头，JDK 21 HttpClient 不支持
 * 自定义 DNS resolver。</p>
 *
 * <p><b>重定向说明：</b>JDK HttpClient 默认 {@code followRedirects=NEVER}，
 * 不自动跟随重定向，因此不存在"重定向到内网地址"的风险。如果未来开启
 * 重定向跟随，需要对 Location 头中的新地址重新执行 {@link #check(URI)}。</p>
 *
 * @author sureai
 * @since 1.2.1
 */
final class SSRFGuard {

	private SSRFGuard() {
	}

	/**
	 * 校验 URI 的 host 解析后的 IP 是否安全。
	 *
	 * <p>解析 host 的所有 IP 地址（防 DNS 轮询绕过），只要有一个是内网/特殊
	 * 地址就拒绝。</p>
	 *
	 * @param uri 目标 URI（scheme 已校验为 http/https）
	 * @return {@code null} 表示安全可放行；非 null 为拒绝原因描述
	 */
	static String check(URI uri) {
		String host = uri.getHost();
		if (host == null || host.isEmpty()) {
			return "missing or empty host";
		}

		InetAddress[] addrs;
		try {
			addrs = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			return "cannot resolve host: " + host;
		}

		if (addrs.length == 0) {
			return "cannot resolve host: " + host;
		}

		for (InetAddress addr : addrs) {
			String reason = classifyDanger(addr);
			if (reason != null) {
				return "blocked private/reserved address " + addr.getHostAddress()
					+ " (" + reason + ") for host: " + host;
			}
		}
		return null;
	}

	/**
	 * 判断单个 IP 是否为内网/保留地址。
	 */
	private static String classifyDanger(InetAddress addr) {
		// 直接检查该地址本身
		String direct = checkSingle(addr);
		if (direct != null) {
			return direct;
		}

		// 额外检查 IPv4-mapped IPv6 地址：::ffff:a.b.c.d
		// JDK 自带的 isLoopbackAddress() 等方法对 ::ffff:127.0.0.1 可能不识别，
		// 需要提取嵌入的 IPv4 地址再判断。
		if (addr instanceof Inet6Address inet6) {
			InetAddress embedded = extractEmbeddedIPv4(inet6);
			if (embedded != null) {
				String inner = checkSingle(embedded);
				if (inner != null) {
					return "IPv4-mapped IPv6 embeds " + inner;
				}
			}
		}
		return null;
	}

	/**
	 * 用 JDK 自带方法检查单个地址是否危险。
	 *
	 * @return 危险原因；null 表示安全
	 */
	private static String checkSingle(InetAddress addr) {
		if (addr.isLoopbackAddress()) {
			return "loopback";
		}
		if (addr.isAnyLocalAddress()) {
			return "any-local (0.0.0.0/::)";
		}
		if (addr.isLinkLocalAddress()) {
			return "link-local";
		}
		if (addr.isSiteLocalAddress()) {
			return "site-local (private)";
		}
		if (addr.isMulticastAddress()) {
			return "multicast";
		}
		return null;
	}

	/**
	 * 如果是 IPv4-mapped IPv6 地址（::ffff:a.b.c.d），提取其中嵌入的 IPv4 地址。
	 *
	 * <p>IPv4-mapped 地址格式：前 10 字节为 0，第 11-12 字节为 0xffff，
	 * 最后 4 字节为 IPv4 地址。</p>
	 *
	 * @return 嵌入的 Inet4Address；如果不是 IPv4-mapped 则返回 null
	 */
	private static InetAddress extractEmbeddedIPv4(Inet6Address addr) {
		byte[] bytes = addr.getAddress();
		// IPv6 地址固定 16 字节
		if (bytes.length != 16) {
			return null;
		}
		// 检查 ::ffff: 前缀：前 10 字节为 0，第 11、12 字节为 0xff 0xff
		for (int i = 0; i < 10; i++) {
			if (bytes[i] != 0) {
				return null;
			}
		}
		if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
			return null;
		}
		// 后 4 字节是 IPv4 地址
		try {
			byte[] ipv4 = new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
			return InetAddress.getByAddress(ipv4);
		} catch (UnknownHostException e) {
			return null;
		}
	}
}
