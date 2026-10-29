package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** L-1: IPv6 clients are keyed on their prefix; only real IP literals are accepted (never resolved via DNS). */
class ClientAddressTest {
    private static String key(String s) { return ClientAddress.key(s, 64); }

    @Test
    void differentSpellingsOfOneIpv6AddressShareAKey() {
        String k = key("2001:db8:0:0:0:0:0:1");
        assertThat(k).isNotNull();
        for (String spelling : List.of("2001:db8::1", "2001:DB8::1", "2001:0db8:0000:0000:0000:0000:0000:0001",
                "2001:db8:0:0::1", "2001:db8::1%eth0", "2001:db8::1%12")) {
            assertThat(key(spelling)).as(spelling).isEqualTo(k);
        }
    }

    @Test
    void addressesInOneSlash64ShareAKeyAndOtherSlash64sDoNot() {
        assertThat(key("2001:db8:1:2::1")).isEqualTo(key("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        assertThat(key("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo(key("2001:db8:1:2::"));
        assertThat(key("2001:db8:1:2::1")).isNotEqualTo(key("2001:db8:1:3::1"));
        assertThat(key("2001:db8:1:2::1")).isNotEqualTo(key("2001:db9:1:2::1"));
    }

    @Test
    void ipv4IsPerAddressAndMappedIpv6IsTheIpv4Address() {
        assertThat(key("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(key("203.0.113.7")).isNotEqualTo(key("203.0.113.8"));
        assertThat(key("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(key("::FFFF:cb00:7107")).isEqualTo("203.0.113.7");
        assertThat(key("0:0:0:0:0:ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(key("::ffff:203.0.113.8")).isNotEqualTo(key("::ffff:203.0.113.7"));
        assertThat(key("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(key("::1")).isEqualTo(key("0:0:0:0:0:0:0:1"));
    }

    @Test
    void embeddedIpv4InAnIpv6AddressIsParsed() {
        assertThat(key("64:ff9b::192.0.2.33")).isEqualTo(key("64:ff9b:0:0:0:0:c000:221"));
    }

    @Test
    void invalidLiteralsAreRejected() {
        List<String> bad = List.of("", " ", "not-an-ip", "dead:beef", "dead:beef:::::", ":::", "1:2:3:4:5:6:7:8:9",
                "1:2:3:4:5:6:7::8", "1::2::3", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "12345::1", "g::1", "::1.2.3",
                "::1.2.3.4.5", "::1.2.3.256", "1.2.3", "1.2.3.4.5.6", "256.1.1.1", "01.2.3.4", "1.2.3.-4", "1..3.4",
                "1.2.3.4%eth0", "::1%", "::1%bad zone", "::1%eth0%eth1", "example.com", "localhost", "1.2.3.4:80",
                "[::1]", "0x7f.0.0.1", "2001:db8::1" + "1".repeat(60), "1.2.3.4" + "5".repeat(60), "١::1");
        for (String s : bad) assertThat(key(s)).as("'" + s + "'").isNull();
        assertThat(ClientAddress.key(null, 64)).isNull();
    }

    @Test
    void prefixLengthIsConfigurable() {
        // /48: the third group counts, the fourth does not
        assertThat(ClientAddress.key("2001:db8:1:2::1", 48)).isEqualTo(ClientAddress.key("2001:db8:1:ffff::9", 48));
        assertThat(ClientAddress.key("2001:db8:1::1", 48)).isNotEqualTo(ClientAddress.key("2001:db8:2::1", 48));
        // /128: every address is its own client
        assertThat(ClientAddress.key("2001:db8::1", 128)).isNotEqualTo(ClientAddress.key("2001:db8::2", 128));
        assertThat(ClientAddress.key("2001:db8::1", 128)).isEqualTo(ClientAddress.key("2001:DB8:0::1", 128));
        // a prefix that ends inside a group masks the rest of that group (/60 ignores the last nibble of group 4)
        assertThat(ClientAddress.key("2001:db8:1:20::1", 60)).isEqualTo(ClientAddress.key("2001:db8:1:2f::1", 60));
        assertThat(ClientAddress.key("2001:db8:1:20::1", 60)).isNotEqualTo(ClientAddress.key("2001:db8:1:30::1", 60));
        // /1: nearly everyone shares a key
        assertThat(ClientAddress.key("2001::1", 1)).isEqualTo(ClientAddress.key("3fff::1", 1));
        // IPv4 is not affected by the setting
        assertThat(ClientAddress.key("10.0.0.1", 128)).isEqualTo("10.0.0.1");
    }

    // ---- through RateLimits.clientIp -------------------------------------------------------------------------

    private static RateLimits limits(boolean trust, int bits) {
        RateLimitProperties p = new RateLimitProperties();
        p.setTrustForwardedHeaders(trust);
        p.setIpv6PrefixBits(bits);
        return new RateLimits(p, JsonMapper.builder().build());
    }

    private static MockHttpServletRequest req(String remote, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/auth/login");
        r.setRemoteAddr(remote);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void peerAddressIsNormalizedAndAnUnparsablePeerIsKeptAsIs() {
        RateLimits l = limits(false, 64);
        assertThat(l.clientIp(req("0:0:0:0:0:0:0:1", null))).isEqualTo(l.clientIp(req("::1", null)));
        assertThat(l.clientIp(req("2001:db8:1:2::1", null))).isEqualTo(l.clientIp(req("2001:db8:1:2::2", null)));
        assertThat(l.clientIp(req("10.0.0.1", null))).isEqualTo("10.0.0.1");
        assertThat(l.clientIp(req("weird-peer", null))).isEqualTo("weird-peer");
    }

    @Test
    void forwardedEntriesAreValidatedAndFallBackToThePeer() {
        RateLimits l = limits(true, 64);
        assertThat(l.clientIp(req("10.0.0.1", "2001:db8:1:2::77"))).isEqualTo(l.clientIp(req("10.9.9.9", "2001:db8:1:2::1")));
        assertThat(l.clientIp(req("10.0.0.1", "::ffff:203.0.113.7"))).isEqualTo("203.0.113.7");
        for (String bad : List.of("dead:beef:::::", "1.2.3.4.5.6", "....", "a:b", "1.2.3.4:80", "localhost", "ab".repeat(30))) {
            assertThat(l.clientIp(req("10.0.0.1", bad))).as(bad).isEqualTo("10.0.0.1");
        }
    }

    @Test
    void theConfiguredPrefixLengthIsUsedByTheLimits() {
        RateLimits l48 = limits(false, 48);
        assertThat(l48.clientIp(req("2001:db8:1:2::1", null))).isEqualTo(l48.clientIp(req("2001:db8:1:9::1", null)));
        RateLimits l128 = limits(false, 128);
        assertThat(l128.clientIp(req("2001:db8:1:2::1", null))).isNotEqualTo(l128.clientIp(req("2001:db8:1:2::2", null)));
    }

    @Test
    void anOutOfRangePrefixLengthFailsAtStartup() {
        for (int bad : new int[] {0, -1, 129}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> limits(false, bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ipv6-prefix-bits");
        }
        limits(false, 1);
        limits(false, 128);
    }
}
