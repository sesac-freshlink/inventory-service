package com.freshlink.inventory.common.web;

import tools.jackson.databind.ObjectMapper;
import com.freshlink.common.error.ErrorResponse;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class XStoreIdFilter extends OncePerRequestFilter {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final ObjectMapper objectMapper;

    public XStoreIdFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/owner") || path.startsWith("/owner/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String storeId = request.getHeader(STORE_ID_HEADER);

        if (storeId == null || storeId.isBlank()) {
            InventoryErrorCode errorCode = InventoryErrorCode.STORE_NOT_LINKED;

            response.setStatus(errorCode.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");

            objectMapper.writeValue(
                    response.getWriter(),
                    new ErrorResponse(
                            errorCode.name(),
                            errorCode.message(),
                            traceId()
                    )
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}