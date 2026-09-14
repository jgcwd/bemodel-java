package com.bemodel.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 安全策略（演示环境的"真安全"）：无状态 JWT；/api/auth/login 放行；
 * GET /api/** 三角色皆可；写操作（POST/PUT/DELETE）仅 ADMIN/EDITOR；
 * 例外：/api/cs/ask、/api/search 为只读语义查询，/api/cs/feedback 为评议提交，三角色皆可。
 * 401/403 统一返回 Result 风格 JSON。CORS 策略与 CorsConfig 现状一致。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/auth/login").permitAll()
                        // 问一问/搜索是只读语义查询：三角色皆可（虽走 POST，但不产生任何写）
                        .requestMatchers(HttpMethod.POST, "/api/cs/ask", "/api/search")
                            .hasAnyRole("ADMIN", "EDITOR", "VIEWER")
                        // 路由反馈：三角色皆可提交（评议）；反馈列表仅管理角色
                        .requestMatchers(HttpMethod.POST, "/api/cs/feedback")
                            .hasAnyRole("ADMIN", "EDITOR", "VIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/cs/feedback/list").hasAnyRole("ADMIN", "EDITOR")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "EDITOR", "VIEWER")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "EDITOR")
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, "未登录或已过期"))
                        .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, "权限不足")));
        return http.build();
    }

    /** 与既有 CorsConfig（WebMvcConfigurer）同一策略：安全链路的预检请求走这里 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static void writeJson(jakarta.servlet.http.HttpServletResponse res, int code, String msg)
            throws IOException {
        res.setStatus(code);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(new ObjectMapper().writeValueAsString(Map.of("code", code, "msg", msg)));
    }
}
