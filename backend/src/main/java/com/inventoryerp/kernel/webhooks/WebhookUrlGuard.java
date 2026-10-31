package com.inventoryerp.kernel.webhooks;

import com.inventoryerp.shared.web.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps outbound webhooks off the server's own network (SSRF). A target must be {@code http} or {@code https}, and
 * every address its host resolves to must be public: loopback, private, link-local (cloud metadata), carrier-grade NAT,
 * unique-local, multicast and wildcard addresses are refused. The check runs when an endpoint is created and again
 * before each delivery, so a host re-pointed to a private address later is still stopped. {@code
 * WEBHOOKS_ALLOW_PRIVATE_TARGETS=true} lifts it for development and tests.
 */
@Component
public class WebhookUrlGuard {

    private final boolean allowPrivate;

    public WebhookUrlGuard(@Value("${WEBHOOKS_ALLOW_PRIVATE_TARGETS:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /** Refuse {@code url} with a 400 when it may not receive webhooks. */
    public void check(String url) {
        String problem = problem(url);
        if (problem != null) {
            throw ApiException.badRequest(problem);
        }
    }

    /** Why {@code url} may not receive webhooks, or null when it may. */
    public String problem(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            return "Webhook URL is not valid.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "Webhook URL must use http or https.";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "Webhook URL has no host.";
        }
        if (allowPrivate) {
            return null;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "Webhook URL host " + host + " cannot be resolved.";
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a)) {
                return "Webhook URL must point to a public address; " + host + " resolves to a private or local one.";
            }
        }
        return null;
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 0.0.0.0/8, carrier-grade NAT 100.64.0.0/10, 192.0.0.0/24, benchmarking 198.18.0.0/15, 240.0.0.0/4.
            return first != 0 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && (b[2] & 0xff) == 0)
                    && !(first == 198 && (second == 18 || second == 19)) && first < 240;
        }
        // IPv6 unique-local fc00::/7; IPv4-mapped addresses are checked as IPv4.
        if ((b[0] & 0xfe) == 0xfc) {
            return false;
        }
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            mapped &= b[i] == 0;
        }
        if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            try {
                return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return true;
    }
}
