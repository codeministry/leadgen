/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses DNS rebinding while {@code security.auth} is {@code none}.
 *
 * <p>With no token to check, the network is the whole of the access control, and a browser crosses
 * it: a page on a name its author points at this machine talks to the API as a same-origin
 * request, reads included, with no preflight to stop it. Such a request always carries the
 * author's name, as the Host and, on anything but a plain GET, as the Origin. So a request is
 * refused with 403 when either names a dotted host nobody configured.
 *
 * <p><b>Every host the request names, not the one Tomcat settled on.</b> With forwarded headers
 * on, {@code getServerName()} is X-Forwarded-Host whenever the peer is private or loopback, which
 * the compose nginx, the docker bridge and a local run all are, and a same-origin page may set that
 * header on a GET without a preflight. So the raw Host and every forwarded value are checked instead:
 * the rebound name is among them as long as no proxy in front passes a client's X-Forwarded-Host on
 * while rewriting the Host. The compose nginx and the dev server's proxy both overwrite it.
 *
 * <p><b>What passes without configuration:</b> loopback ({@code localhost} and below it, {@code
 * 127.0.0.0/8}, {@code ::1}), IP literals and single-label names. None of them is a name a public
 * DNS answers for somebody else's page: the compose stack reaches the api as {@code localhost} or
 * {@code api}, a kubelet probe by the pod's IP. A page is held to more: it passes when it is local,
 * configured, or served from the very origin the request was sent to, host and port, which is how
 * the UI opened by a LAN address or a bare machine name writes. A deployment under {@code none}
 * behind a dotted host name lists it in {@code leadgen.security.allowed-hosts} ({@code ALLOWED_HOSTS}).
 *
 * <p>The container's health question stays open whatever it is asked under. Under {@code oidc} this
 * filter is not installed at all: the token is the guard, and a browser-based MCP client on any
 * origin is served with one.
 */
@Slf4j
class RebindingGuard extends OncePerRequestFilter {

    private final Set<String> allowed;

    /** The container's health question, from the same patterns the oidc chain leaves open. */
    private final RequestMatcher health = new OrRequestMatcher(SecurityConfig.HEALTH.stream()
            .map(pattern ->
                    (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(pattern))
            .toList());

    /**
     * Entries are read the way an operator writes them: a scheme and a port copied from the address
     * bar and a trailing dot are dropped, so the entry names the host it was meant to name rather
     * than silently matching nothing.
     */
    RebindingGuard(Collection<String> allowedHosts) {
        allowedHosts.stream()
                .filter(entry -> entry.contains("*"))
                .forEach(entry -> log.warn(
                        "ALLOWED_HOSTS entry '{}' is a pattern and matches nothing: list each host by its name",
                        entry));
        this.allowed = allowedHosts.stream()
                .map(RebindingGuard::entryHost)
                .filter(name -> name != null)
                .map(RebindingGuard::normalised)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return health.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Only what the client sent: getServerName() is derived from these two, and with neither
        // Tomcat falls back to its own name, which the client never used.
        List<String> hosts = new ArrayList<>();
        hosts.add(withoutPort(request.getHeader("Host")));
        for (String forwarded : Collections.list(request.getHeaders("X-Forwarded-Host"))) {
            for (String value : forwarded.split(",")) {
                hosts.add(withoutPort(value));
            }
        }
        hosts.removeIf(host -> host == null);
        for (String host : hosts) {
            if (!isAllowed(host)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "host not allowed");
                return;
            }
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !isAllowedPage(Page.of(origin), hosts, request.getServerPort())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "origin not allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Whether this process may be reached under a host: loopback, IP literals, single-label names
     * (a Compose service, a pod IP asked by a probe) and the configured names.
     */
    boolean isAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String name = normalised(host);
        return allowed.contains(name) || !name.contains(".") || isLocalName(name) || literal(name) != null;
    }

    /**
     * Whether a page may talk to the API unauthenticated: a local one, a configured host, or the
     * origin the request itself was sent to, host and port, the host having passed {@link
     * #isAllowed} already. The server's port is the one the browser used: Tomcat reads it from the
     * Host, or from X-Forwarded-Port behind a proxy that says it forwarded. A page on any other IP
     * literal, service name or port is somebody else's page, sending a cross-origin request.
     */
    boolean isAllowedPage(Page page, Collection<String> requestHosts, int serverPort) {
        if (page == null) {
            return false;
        }
        String name = normalised(page.host());
        return allowed.contains(name)
                || isLocal(name)
                || (page.port() == serverPort
                        && requestHosts.stream().map(RebindingGuard::normalised).anyMatch(name::equals));
    }

    /** Loopback by name or by address: {@code localhost} and below it, {@code 127.0.0.0/8}, {@code ::1}. */
    private static boolean isLocal(String name) {
        if (isLocalName(name)) {
            return true;
        }
        InetAddress address = literal(name);
        return address != null && address.isLoopbackAddress();
    }

    private static boolean isLocalName(String name) {
        return name.equals("localhost") || name.endsWith(".localhost");
    }

    /**
     * The address an IP literal names, IPv4 or IPv6, or null for anything that is a name. A literal
     * starts with a digit or holds a colon; anything else is answered without the parser's exception.
     */
    private static InetAddress literal(String name) {
        if (name.isEmpty() || !(Character.isDigit(name.charAt(0)) || name.indexOf(':') >= 0)) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Lower case, IPv6 brackets and a trailing dot removed: {@code LeadGen.Lan.} and {@code leadgen.lan} are one host. */
    private static String normalised(String host) {
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    /** A Host header's name without its port; {@code [::1]:8080} keeps its brackets. Null stays null. */
    static String withoutPort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String host = value.trim();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.indexOf(':');
        // One colon is a port; more than one is a bare IPv6 literal, kept whole.
        return colon >= 0 && colon == host.lastIndexOf(':') ? host.substring(0, colon) : host;
    }

    /**
     * An allowed-hosts entry's host: {@code http://leadgen.lan:4200/} names {@code leadgen.lan}, and
     * {@code bücher.lan} its punycode name. {@link IDN} converts by IDNA2003 and a browser by UTS-46,
     * which differ for ß, ς and the joiners ({@code straße.lan} becomes {@code strasse.lan}); such a
     * name is listed in the punycode the browser sends.
     */
    private static String entryHost(String entry) {
        String value = entry.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        String host = withoutPort(slash >= 0 ? value.substring(0, slash) : value);
        try {
            return host == null || host.startsWith("[") ? host : IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return host;
        }
    }

    /**
     * The page an Origin ({@code scheme://host[:port]}) names, the port being the scheme's default
     * when none is written.
     *
     * @param host as written, brackets and all
     * @param port explicit or the scheme's default, -1 for a scheme without one
     */
    record Page(String host, int port) {

        /**
         * Read by hand, and null for {@code null} and anything of another shape: {@code
         * URI.getHost()} answers null for names a browser serves, {@code my_box.lan} among them.
         */
        static Page of(String origin) {
            String value = origin.trim();
            int scheme = value.indexOf("://");
            if (scheme <= 0) {
                return null;
            }
            String authority = value.substring(scheme + 3);
            if (authority.isEmpty() || authority.contains("/") || authority.contains("@")) {
                return null;
            }
            String host = withoutPort(authority);
            String rest = authority.substring(host.length());
            if (rest.isEmpty()) {
                return new Page(
                        host,
                        switch (value.substring(0, scheme).toLowerCase(Locale.ROOT)) {
                            case "http" -> 80;
                            case "https" -> 443;
                            default -> -1;
                        });
            }
            try {
                return new Page(host, Integer.parseInt(rest.substring(1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
