package dev.oncall.sample.order;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 요청 로그는 남기지 않고, 느린 요청만 경고로 남긴다. */
@Component
class SlowRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SlowRequestFilter.class);
    private static final long THRESHOLD_MS = 2000;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= THRESHOLD_MS) {
                log.warn("Slow request: {} {} took {}ms status={}", req.getMethod(), req.getRequestURI(), ms,
                        res.getStatus());
            }
        }
    }
}
