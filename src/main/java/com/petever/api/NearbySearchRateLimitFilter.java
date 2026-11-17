package com.petever.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

@Component
@Profile("supabase")
class NearbySearchRateLimitFilter extends OncePerRequestFilter {
    private static final String PATH = "/api/veterinary-clinics/nearby-search";
    private static final String OVERFLOW = "overflow";
    private final int maxRequests;
    private final long windowMillis;
    private final int maxKeys;
    private final Clock clock;
    private final Map<String, Integer> counts = new HashMap<>();
    private long window = Long.MIN_VALUE;

    @Autowired
    NearbySearchRateLimitFilter(
            @Value("${petever.rate-limit.nearby-search.max-requests:30}") int maxRequests,
            @Value("${petever.rate-limit.nearby-search.window-seconds:60}") long windowSeconds,
            @Value("${petever.rate-limit.nearby-search.max-keys:10000}") int maxKeys) {
        this(maxRequests, windowSeconds, maxKeys, Clock.systemUTC());
    }

    NearbySearchRateLimitFilter(int maxRequests, long windowSeconds, int maxKeys, Clock clock) {
        if (maxRequests < 1 || windowSeconds < 1 || maxKeys < 1)
            throw new IllegalArgumentException("Nearby search rate limit values must be positive");
        this.maxRequests = maxRequests;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1000);
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATH.equals(requestPath(request));
    }

    static String requestPath(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long retryAfter = retryAfter(clientKey(request.getRemoteAddr()));
        if (retryAfter == 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/plain");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("Too many requests");
    }

    private synchronized long retryAfter(String key) {
        long now = clock.millis();
        long current = Math.floorDiv(now, windowMillis);
        if (current != window) {
            counts.clear();
            window = current;
        }
        if (!counts.containsKey(key) && counts.size() >= maxKeys - 1) key = OVERFLOW;
        int count = counts.getOrDefault(key, 0);
        if (count >= maxRequests) {
            long remaining = Math.max(1, Math.multiplyExact(current + 1, windowMillis) - now);
            return Math.max(1, Math.ceilDiv(remaining, 1000));
        }
        counts.put(key, count + 1);
        return 0;
    }

    synchronized int trackedKeys() { return counts.size(); }

    static String clientKey(String remoteAddress) {
        if (remoteAddress == null) return "unknown";
        String value = remoteAddress;
        int scope = value.indexOf('%');
        if (scope >= 0 && value.indexOf(':') >= 0) value = value.substring(0, scope);
        boolean ipv6 = value.indexOf(':') >= 0;
        if (ipv6 ? !value.matches("[0-9A-Fa-f:.]+") : !value.matches("\\d{1,3}(\\.\\d{1,3}){3}"))
            return "unknown";
        try {
            byte[] bytes = InetAddress.getByName(value).getAddress();
            if (bytes.length == 4) return "4:" + InetAddress.getByAddress(bytes).getHostAddress();
            if (isMappedIpv4(bytes))
                return "4:" + InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)).getHostAddress();
            return "6:" + HexFormat.of().formatHex(bytes, 0, 8);
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static boolean isMappedIpv4(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) return false;
        for (int index = 0; index < 10; index++) if (bytes[index] != 0) return false;
        return true;
    }
}
