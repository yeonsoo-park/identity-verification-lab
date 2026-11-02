package idvlab.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import idvlab.common.CommonErrorCode;
import idvlab.common.FailureResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
                                "/api/v1/auth/**", "/lab/**").permitAll()
                        .anyRequest().hasRole("USER"))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> write(res, CommonErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) -> write(res, CommonErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private void write(HttpServletResponse res, CommonErrorCode code) throws IOException {
        res.setStatus(code.getStatus().value());
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(FailureResponse.of(code)));
    }
}
