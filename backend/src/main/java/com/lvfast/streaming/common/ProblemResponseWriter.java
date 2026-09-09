package com.lvfast.streaming.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

public final class ProblemResponseWriter {

    private ProblemResponseWriter() {
    }

    public static void write(
            HttpServletResponse response,
            HttpServletRequest request,
            int status,
            String code,
            String detail) throws IOException {
        Object existing = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = existing == null ? UUID.randomUUID().toString() : existing.toString();
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        response.getWriter().write("{"
                + "\"type\":\"https://stream.lvfast.site/problems/" + code.toLowerCase().replace('_', '-') + "\","
                + "\"title\":\"" + title(status) + "\","
                + "\"status\":" + status + ","
                + "\"detail\":\"" + detail + "\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"code\":\"" + code + "\"}");
    }

    private static String title(int status) {
        return switch (status) {
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 429 -> "Too Many Requests";
            default -> "Request Failed";
        };
    }
}
