package com.example.wallet.security;

/**
 * Turns the text of a client address into the key its rate-limit buckets use. Parsing is done by hand on purpose: the
 * text may come from an attacker-controlled header, so nothing here may resolve names (no {@code InetAddress.getByName}).
 * <ul>
 *   <li>IPv4: the canonical dotted form, one key per address.</li>
 *   <li>IPv4-mapped IPv6 ({@code ::ffff:a.b.c.d}): the IPv4 address.</li>
 *   <li>IPv6: the first {@code ipv6PrefixBits} bits (default 64), so a host that owns a whole /64 cannot get a fresh
 *       bucket by rotating addresses. Zone ids ({@code %eth0}), letter case and {@code ::} compression do not matter.</li>
 * </ul>
 */
final class ClientAddress {
    /** Longest accepted text: an IPv6 address with an embedded IPv4 is 45 characters, plus a short zone id. */
    private static final int MAX_LENGTH = 64;

    private ClientAddress() { }

    /**
     * @param text           an IPv4 or IPv6 literal, optionally with an IPv6 zone id
     * @param ipv6PrefixBits how many leading bits of an IPv6 address identify the client (1 to 128)
     * @return the bucket key, or null when the text is not a valid IP literal
     */
    static String key(String text, int ipv6PrefixBits) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) return null;
        String addr = text;
        int zone = text.indexOf('%');
        if (zone >= 0) {
            if (!validZone(text.substring(zone + 1))) return null;
            addr = text.substring(0, zone);
        }
        if (addr.indexOf(':') < 0) {
            if (zone >= 0) return null; // a zone id only exists on IPv6
            int[] v4 = parseIpv4(addr);
            return v4 == null ? null : v4[0] + "." + v4[1] + "." + v4[2] + "." + v4[3];
        }
        int[] g = parseIpv6(addr);
        if (g == null) return null;
        boolean mapped = g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 && g[4] == 0 && g[5] == 0xffff;
        if (mapped) return (g[6] >> 8) + "." + (g[6] & 0xff) + "." + (g[7] >> 8) + "." + (g[7] & 0xff);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int covered = Math.max(0, Math.min(16, ipv6PrefixBits - 16 * i));
            int masked = covered == 0 ? 0 : g[i] & (0xffff << (16 - covered)) & 0xffff;
            if (i > 0) sb.append(':');
            sb.append(Integer.toHexString(masked));
        }
        return sb.toString();
    }

    private static boolean validZone(String z) {
        if (z.isEmpty() || z.length() > 32) return false;
        for (int i = 0; i < z.length(); i++) {
            char c = z.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '_' || c == '-' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    /** Strict dotted quad: four decimal numbers of 1 to 3 digits, 0 to 255, no leading zeros (they are ambiguous). */
    private static int[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) return null;
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') return null;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return null;
            out[i] = v;
        }
        return out;
    }

    /** Eight 16-bit groups of an IPv6 literal (one "::" allowed, IPv4 allowed as the last 32 bits), or null. */
    private static int[] parseIpv6(String s) {
        int dc = s.indexOf("::");
        if (dc >= 0 && s.indexOf("::", dc + 1) >= 0) return null; // two "::" (this also catches ":::")
        int[] head = new int[8];
        if (dc < 0) {
            return groups(s, head, true) == 8 ? head : null;
        }
        int[] tail = new int[8];
        int h = groups(s.substring(0, dc), head, false);
        if (h < 0) return null;
        int t = groups(s.substring(dc + 2), tail, true);
        if (t < 0 || h + t > 7) return null; // "::" stands for at least one group
        int[] g = new int[8];
        System.arraycopy(head, 0, g, 0, h);
        System.arraycopy(tail, 0, g, 8 - t, t);
        return g;
    }

    /** Parses colon separated groups into out; returns the count, or -1 if invalid. Empty text is zero groups. */
    private static int groups(String s, int[] out, boolean allowIpv4Last) {
        if (s.isEmpty()) return 0;
        String[] parts = s.split(":", -1);
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (allowIpv4Last && i == parts.length - 1 && p.indexOf('.') >= 0) {
                int[] v4 = parseIpv4(p);
                if (v4 == null || n + 2 > 8) return -1;
                out[n++] = (v4[0] << 8) | v4[1];
                out[n++] = (v4[2] << 8) | v4[3];
                continue;
            }
            if (p.isEmpty() || p.length() > 4 || n >= 8) return -1;
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) return -1; // ASCII hex only
                v = v * 16 + Character.digit(c, 16);
            }
            out[n++] = v;
        }
        return n;
    }
}
