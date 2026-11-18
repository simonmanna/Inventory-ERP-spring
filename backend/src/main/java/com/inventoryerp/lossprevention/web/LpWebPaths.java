package com.inventoryerp.lossprevention.web;

import jakarta.servlet.http.HttpServletRequest;

/** Where this API is served, as the client sees it, for links the backend hands out (token URL, live stream). */
final class LpWebPaths {

    private static final String MODULE = "/loss-prevention/";

    private LpWebPaths() {}

    /** The path before {@code /loss-prevention/} in the request, e.g. {@code /api/v1}. */
    static String apiBase(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int at = uri == null ? -1 : uri.indexOf(MODULE);
        return at < 0 ? "/api/v1" : uri.substring(0, at);
    }
}
