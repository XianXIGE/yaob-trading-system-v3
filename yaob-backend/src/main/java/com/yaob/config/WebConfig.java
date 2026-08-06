package com.yaob.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("https://jarvis-wx.cloud", "http://150.158.91.2:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录/注册限流拦截器
        registry.addInterceptor(new RateLimitInterceptor())
                .addPathPatterns("/api/login", "/api/register");
        
        // 认证拦截器
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/login",
                        "/api/register"
                );
    }

    /**
     * 登录/注册限流: 每IP每分钟最多10次请求
     */
    class RateLimitInterceptor implements HandlerInterceptor {
        private static final int MAX_REQUESTS = 10;
        private static final long WINDOW_MS = 60_000L;
        private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return true;
            }
            String ip = getClientIp(request);
            long now = System.currentTimeMillis();
            
            RateBucket bucket = buckets.compute(ip, (k, v) -> {
                if (v == null || now - v.windowStart > WINDOW_MS) {
                    return new RateBucket(now);
                }
                v.count.incrementAndGet();
                return v;
            });
            
            if (bucket.count.get() > MAX_REQUESTS) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", 429, "msg", "请求过于频繁，请稍后再试")));
                return false;
            }
            return true;
        }

        private String getClientIp(HttpServletRequest request) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty()) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip == null || ip.isEmpty()) {
                ip = request.getRemoteAddr();
            }
            // 取第一个IP（多层代理时）
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return ip != null ? ip : "unknown";
        }
    }

    static class RateBucket {
        final long windowStart;
        final AtomicInteger count;
        RateBucket(long windowStart) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(1);
        }
    }

    class AuthInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return true;
            }
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute("userId") == null) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", 401, "msg", "未登录")));
                return false;
            }
            return true;
        }
    }
}
