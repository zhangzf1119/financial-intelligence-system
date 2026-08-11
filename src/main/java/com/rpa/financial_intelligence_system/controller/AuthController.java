package com.rpa.financial_intelligence_system.controller;

import com.rpa.financial_intelligence_system.common.ApiResponse;
import com.rpa.financial_intelligence_system.security.JwtService;
import jakarta.validation.Valid; import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.simple.JdbcClient; import org.springframework.security.authentication.*; import org.springframework.security.core.Authentication; import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.AuthenticationException;
import java.util.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.rpa.financial_intelligence_system.service.RedisSecurityService;
import com.rpa.financial_intelligence_system.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;

@Tag(name="身份认证") @RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager auth; private final JwtService jwt; private final JdbcClient jdbc; private final RedisSecurityService redis; private final AuditLogService logs;
    public AuthController(AuthenticationManager auth, JwtService jwt, JdbcClient jdbc,RedisSecurityService redis,AuditLogService logs){this.auth=auth;this.jwt=jwt;this.jdbc=jdbc;this.redis=redis;this.logs=logs;}
    record Login(@NotBlank String username,@NotBlank String password){}
    @PostMapping("/login") ApiResponse<?> login(@Valid @RequestBody Login q,HttpServletRequest req){String ip=clientIp(req),agent=req.getHeader("User-Agent");if(!redis.loginAllowed(q.username,ip)){logs.login(q.username,ip,agent,false,"登录失败次数过多");throw new IllegalArgumentException("登录失败次数过多，请 10 分钟后重试");}try{auth.authenticate(new UsernamePasswordAuthenticationToken(q.username,q.password));redis.loginSucceeded(q.username,ip);String token=jwt.create(q.username);Duration ttl=Duration.ofSeconds(jwt.remainingSeconds(token));redis.registerSession(q.username,token,ttl);if(!isSuperAdmin(q.username))redis.bindSingleSession(q.username,token,ttl);logs.login(q.username,ip,agent,true,"登录成功");return ApiResponse.ok(Map.of("token",token,"user",profile(q.username)));}catch(AuthenticationException e){redis.loginFailed(q.username,ip);logs.login(q.username,ip,agent,false,"用户名或密码错误");throw new IllegalArgumentException("用户名或密码错误");}}
    @PostMapping("/logout") ApiResponse<?> logout(@RequestHeader("Authorization")String header){String token=header.replaceFirst("^Bearer ","");String username=jwt.parse(token);redis.clearSingleSession(username,token);redis.unregisterSession(username,token);redis.blacklist(token,Duration.ofSeconds(jwt.remainingSeconds(token)));return ApiResponse.ok();}
    @GetMapping("/me") ApiResponse<?> me(Authentication a){return ApiResponse.ok(profile(a.getName()));}
    private Map<String,Object> profile(String username){
        var user=new LinkedHashMap<>(jdbc.sql("SELECT u.id,u.username,u.nickname,u.email,u.phone,u.department_id,d.name department_name FROM sys_user u LEFT JOIN sys_department d ON d.id=u.department_id WHERE u.username=:u").param("u",username).query().singleRow());
        user.put("roles",jdbc.sql("SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id JOIN sys_user u ON u.id=ur.user_id WHERE u.username=:u").param("u",username).query(String.class).list());
        user.put("permissions",jdbc.sql("SELECT DISTINCT m.permission FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id=m.id JOIN sys_user_role ur ON ur.role_id=rm.role_id JOIN sys_user u ON u.id=ur.user_id WHERE u.username=:u AND m.permission IS NOT NULL").param("u",username).query(String.class).list());
        user.put("menus",jdbc.sql("SELECT DISTINCT m.id,m.parent_id,m.name,m.path,m.icon,m.type,m.sort_order FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id=m.id JOIN sys_user_role ur ON ur.role_id=rm.role_id JOIN sys_user u ON u.id=ur.user_id WHERE u.username=:u AND m.visible ORDER BY m.sort_order").param("u",username).query().listOfRows()); return user;
    }
    private String clientIp(HttpServletRequest req){String x=req.getHeader("X-Forwarded-For");return x==null?req.getRemoteAddr():x.split(",")[0].trim();}
    private boolean isSuperAdmin(String username){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id JOIN sys_user u ON u.id=ur.user_id WHERE u.username=:u AND r.code='SUPER_ADMIN')").param("u",username).query(Boolean.class).single();}
}
