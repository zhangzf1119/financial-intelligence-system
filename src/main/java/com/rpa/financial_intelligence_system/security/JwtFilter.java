package com.rpa.financial_intelligence_system.security;

import jakarta.servlet.*; import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import com.rpa.financial_intelligence_system.service.RedisSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpa.financial_intelligence_system.common.ApiResponse;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt; private final UserPrincipalService users; private final RedisSecurityService redis; private final ObjectMapper mapper;
    public JwtFilter(JwtService jwt, UserPrincipalService users,RedisSecurityService redis,ObjectMapper mapper) { this.jwt=jwt; this.users=users; this.redis=redis;this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var header=req.getHeader("Authorization");
        if (header!=null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication()==null) {
            try { var token=header.substring(7); if(redis.blacklisted(token)){chain.doFilter(req,res);return;}String username=jwt.parse(token);var user=users.loadUserByUsername(username);boolean admin=user.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_SUPER_ADMIN"));if(!admin&&!redis.singleSessionValid(username,token)){res.setStatus(401);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getWriter(),new ApiResponse<>(401,"账号已在其他设备登录，请重新登录",null));return;}SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities())); } catch(Exception ignored) {}
        }
        chain.doFilter(req,res);
    }
}
