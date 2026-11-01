package com.petever.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class NearbySearchRateLimitFilterTests {
    private static final String PATH = "/api/veterinary-clinics/nearby-search";

    @Test
    void limitsByRemoteAddressAndResetsAtNextWindow() throws Exception {
        TestClock clock = new TestClock(Instant.parse("2026-11-01T00:00:30Z"));
        var filter = new NearbySearchRateLimitFilter(2, 60, 10, clock);

        assertEquals(200, call(filter, request("203.0.113.10", "spoofed-1")).getStatus());
        assertEquals(200, call(filter, request("203.0.113.10", "spoofed-2")).getStatus());
        MockHttpServletResponse blocked = call(filter, request("203.0.113.10", "spoofed-3"));

        assertEquals(429, blocked.getStatus());
        assertEquals("30", blocked.getHeader("Retry-After"));
        assertEquals("no-store", blocked.getHeader("Cache-Control"));
        assertEquals("text/plain;charset=UTF-8", blocked.getContentType());
        assertEquals("Too many requests", blocked.getContentAsString());

        clock.advance(Duration.ofSeconds(31));
        assertEquals(200, call(filter, request("203.0.113.10", null)).getStatus());
        assertEquals(1, filter.trackedKeys());
    }

    @Test
    void encodedRequestUriStillUsesDecodedServletPath() throws Exception {
        var filter = new NearbySearchRateLimitFilter(1, 60, 10, Clock.systemUTC());
        MockHttpServletRequest first = request("203.0.113.11", null);
        first.setRequestURI("/api/veterinary-clinics/%6Eearby-search");
        MockHttpServletRequest second = request("203.0.113.11", null);
        second.setRequestURI("/api/veterinary-clinics/%6Eearby-search");
        assertEquals(200, call(filter, first).getStatus());
        assertEquals(429, call(filter, second).getStatus());
    }

    @Test
    void normalizesIpv6PrefixesAndMappedIpv4() {
        assertEquals(NearbySearchRateLimitFilter.clientKey("2001:db8:abcd:12::1"),
                NearbySearchRateLimitFilter.clientKey("2001:db8:abcd:12:ffff::2"));
        assertEquals(NearbySearchRateLimitFilter.clientKey("fe80::1"),
                NearbySearchRateLimitFilter.clientKey("fe80::2%eth0"));
        assertEquals(NearbySearchRateLimitFilter.clientKey("192.0.2.10"),
                NearbySearchRateLimitFilter.clientKey("::ffff:192.0.2.10"));
        assertEquals("unknown", NearbySearchRateLimitFilter.clientKey("not-an-ip"));
        assertEquals("unknown", NearbySearchRateLimitFilter.clientKey("cafe"));
    }

    @Test
    void boundsTrackedKeysAndSharesOverflowBucket() throws Exception {
        var filter = new NearbySearchRateLimitFilter(1, 60, 2, Clock.systemUTC());

        assertEquals(200, call(filter, request("203.0.113.1", null)).getStatus());
        assertEquals(200, call(filter, request("203.0.113.2", null)).getStatus());
        assertEquals(429, call(filter, request("203.0.113.3", null)).getStatus());
        assertEquals(429, call(filter, request("203.0.113.1", null)).getStatus());
        assertEquals(2, filter.trackedKeys());
    }

    @Test
    void ignoresOtherPathsAndRejectsInvalidConfiguration() throws Exception {
        var filter = new NearbySearchRateLimitFilter(1, 60, 1, Clock.systemUTC());
        MockHttpServletRequest request = request("203.0.113.1", null);
        request.setServletPath("/api/veterinary-clinics/regions");
        request.setRequestURI("/api/veterinary-clinics/regions");
        assertEquals(200, call(filter, request).getStatus());
        assertEquals(0, filter.trackedKeys());

        assertThrows(IllegalArgumentException.class,
                () -> new NearbySearchRateLimitFilter(0, 60, 1, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,
                () -> new NearbySearchRateLimitFilter(1, 0, 1, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,
                () -> new NearbySearchRateLimitFilter(1, 60, 0, Clock.systemUTC()));
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        var request = new MockHttpServletRequest("POST", PATH);
        request.setServletPath(PATH);
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    private static MockHttpServletResponse call(NearbySearchRateLimitFilter filter,
            MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class TestClock extends Clock {
        private Instant now;
        private TestClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
