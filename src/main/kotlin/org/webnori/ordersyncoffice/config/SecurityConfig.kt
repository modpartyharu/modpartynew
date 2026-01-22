package org.webnori.ordersyncoffice.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationSuccessHandler

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    // 정적 리소스 및 API 문서
                    .requestMatchers("/css/**", "/js/**", "/docs/**", "/api-docs/**", "/v3/api-docs/**").permitAll()
                    // OAuth 관련 엔드포인트 (로그인, 인증, 콜백)
                    .requestMatchers("/auth/**", "/oauth/**").permitAll()
                    // 나머지는 세션 기반 인증 필요 (OAuth 성공 후 세션에 저장된 siteCode로 확인)
                    .anyRequest().authenticated()
            }
            // OAuth 인증만 사용 - 폼 로그인 제거
            .formLogin { form -> form.disable() }
            // 인증되지 않은 요청은 로그인 페이지로 리다이렉트
            .exceptionHandling { exception ->
                exception.authenticationEntryPoint { _, response, _ ->
                    response.sendRedirect("/auth/login")
                }
            }
            .logout { logout ->
                logout
                    .logoutUrl("/auth/logout")
                    .logoutSuccessUrl("/auth/login?logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll()
            }
            .csrf { csrf -> csrf.disable() }

        return http.build()
    }
}
