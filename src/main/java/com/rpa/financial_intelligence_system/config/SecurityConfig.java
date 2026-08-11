package com.rpa.financial_intelligence_system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpa.financial_intelligence_system.common.ApiResponse;
import com.rpa.financial_intelligence_system.security.JwtFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.*; import org.springframework.security.authentication.*; import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity; import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy; import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain; import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*; import java.util.List;

@Configuration @EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration c)throws Exception{return c.getAuthenticationManager();}
    @Bean CorsConfigurationSource cors(){ var c=new CorsConfiguration(); c.setAllowedOriginPatterns(List.of("http://localhost:*","http://127.0.0.1:*")); c.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS")); c.setAllowedHeaders(List.of("*")); var s=new UrlBasedCorsConfigurationSource(); s.registerCorsConfiguration("/**",c); return s; }
    @Bean SecurityFilterChain chain(HttpSecurity h, JwtFilter filter, ObjectMapper mapper)throws Exception{return h.csrf(x->x.disable()).cors(x->{}).sessionManagement(x->x.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(x->x.dispatcherTypeMatchers(DispatcherType.ASYNC,DispatcherType.ERROR).permitAll().requestMatchers("/api/auth/login","/ws/chat","/actuator/health","/swagger-ui.html","/swagger-ui/**","/v3/api-docs/**").permitAll().anyRequest().authenticated())
      .exceptionHandling(x->x.authenticationEntryPoint((q,r,e)->{r.setStatus(401);r.setContentType("application/json;charset=UTF-8");mapper.writeValue(r.getWriter(),new ApiResponse<>(401,"登录已过期",null));}))
      .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();}
}
