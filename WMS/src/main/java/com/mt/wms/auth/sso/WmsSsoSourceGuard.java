package com.mt.wms.auth.sso;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /api/v1/auth/sso/exchange} 的来源地址校验（止血措施）。
 *
 * <p>为什么需要：exchange 接受票据后直接建立本地会话。若签票密钥泄露，
 * 任何能访问 WMS 端口的人都能换取任意用户（含 admin）的会话。
 *
 * <p><b>这是临时缓解，不是漏洞修复。</b>根因是密钥泄露，只有轮换密钥才能消除。
 *
 * <p><b>来源判定规则（G5 修正）</b>：
 *
 * <ol>
 *   <li>默认只用 {@link HttpServletRequest#getRemoteAddr()}，即真实 TCP peer。
 *   <li>{@code X-Forwarded-For} <b>仅在</b>直连 peer 命中 {@code wms.sso.trusted-proxies}
 *       时才被采信。否则忽略该头——防止攻击者直连时自带
 *       {@code X-Forwarded-For: 127.0.0.1} 冒充本机。
 *   <li>允许列表为空 → <b>拒绝全部</b>（fail-closed），不再表示"放行"。
 *   <li>地址无法解析 → 拒绝。
 * </ol>
 */
@Component
public class WmsSsoSourceGuard {

    private static final Logger log = LoggerFactory.getLogger(WmsSsoSourceGuard.class);

    private final List<Cidr> allowed;
    private final List<Cidr> trustedProxies;

    public WmsSsoSourceGuard(WmsSsoProperties properties) {
        this.allowed = parse(properties.getExchangeAllowedCidrs());
        this.trustedProxies = parse(properties.getTrustedProxies());

        if (this.allowed.isEmpty()) {
            // 空允许列表 = 全部拒绝。这是 fail-closed，不再回退到"不限制"。
            log.error("wms.sso.exchange-allowed-cidrs 为空：exchange 端点将拒绝所有来源（fail-closed）。"
                    + "如需启用，请配置可信来源（例如 127.0.0.1/32,<BFF-IP>/32）。");
        } else {
            log.info("exchange 来源限制已启用: allowed={} trustedProxies={}",
                    this.allowed.size(), this.trustedProxies.size());
        }
    }

    /** @return true 表示来源被允许；配置为空或无法判定时返回 false。 */
    public boolean isAllowed(HttpServletRequest request) {
        if (allowed.isEmpty()) {
            return false;
        }

        String peer = request.getRemoteAddr();
        String effective = peer;

        // 从 TCP peer 向左遍历代理链，止于最近的不受信 hop。
        // 客户端可控的左侧 XFF 前缀不能覆盖由反代追加的真实来源。
        if (!trustedProxies.isEmpty() && isTrustedPeer(peer)) {
            String header = request.getHeader("X-Forwarded-For");
            if (header != null) {
                if (header.isBlank() || header.length() > 2048) return false;
                String[] chain = header.split(",", -1);
                if (chain.length > 32) return false;
                for (int i = chain.length - 1; i >= 0 && isTrustedPeer(effective); i--) {
                    String hop = chain[i].trim();
                    if (!isLiteralIp(hop)) return false;
                    effective = hop;
                }
            }
        } else if (request.getHeader("X-Forwarded-For") != null) {
            // 非受信 peer 却带了 XFF：记录但忽略，使用真实 peer
            log.warn("忽略来自非受信 peer 的 X-Forwarded-For: peer={}", peer);
        }

        if (!isLiteralIp(effective)) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(effective);
            for (Cidr cidr : allowed) {
                if (cidr.contains(addr)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("无法解析来源地址，拒绝: {}", effective);
            return false;
        }
        log.warn("拒绝非可信来源的 exchange 请求: peer={} effective={}", peer, effective);
        return false;
    }

    private boolean isTrustedPeer(String peer) {
        if (!isLiteralIp(peer)) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(peer);
            for (Cidr cidr : trustedProxies) {
                if (cidr.contains(addr)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean isLiteralIp(String value) {
        if (value == null || !value.matches("[0-9a-fA-F:.]+")) return false;
        if (value.indexOf(':') >= 0) return true;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static List<Cidr> parse(String spec) {
        List<Cidr> list = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return list;
        }
        for (String part : spec.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            try {
                list.add(Cidr.parse(item));
            } catch (Exception e) {
                log.error("无效的 CIDR 配置项，已忽略: {}", item);
            }
        }
        return list;
    }

    /** 极简 CIDR 匹配（IPv4/IPv6），避免引入额外依赖。 */
    static final class Cidr {
        private final byte[] network;
        private final int prefixBits;

        private Cidr(byte[] network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        static Cidr parse(String spec) throws Exception {
            String addrPart = spec;
            int bits = -1;
            int slash = spec.indexOf('/');
            if (slash >= 0) {
                addrPart = spec.substring(0, slash);
                bits = Integer.parseInt(spec.substring(slash + 1));
            }
            InetAddress ia = InetAddress.getByName(addrPart);
            byte[] bytes = ia.getAddress();
            if (bits < 0) {
                bits = bytes.length * 8;
            }
            if (bits < 0 || bits > bytes.length * 8) {
                throw new IllegalArgumentException("prefix out of range");
            }
            return new Cidr(bytes, bits);
        }

        boolean contains(InetAddress candidate) {
            byte[] other = candidate.getAddress();
            if (other.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            int remainder = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != other[i]) {
                    return false;
                }
            }
            if (remainder == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainder) & 0xFF;
            return (network[fullBytes] & mask) == (other[fullBytes] & mask);
        }
    }
}
