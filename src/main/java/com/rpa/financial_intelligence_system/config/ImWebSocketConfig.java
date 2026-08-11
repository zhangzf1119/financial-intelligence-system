package com.rpa.financial_intelligence_system.config;

import com.rpa.financial_intelligence_system.security.JwtService;
import com.rpa.financial_intelligence_system.service.ImSocketBroker;
import com.rpa.financial_intelligence_system.service.RedisSecurityService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.Map;

@Configuration
@EnableWebSocket
public class ImWebSocketConfig implements WebSocketConfigurer {
 private final ImSocketBroker broker; private final JwtService jwt; private final RedisSecurityService redis; private final JdbcClient jdbc;
 public ImWebSocketConfig(ImSocketBroker broker,JwtService jwt,RedisSecurityService redis,JdbcClient jdbc){this.broker=broker;this.jwt=jwt;this.redis=redis;this.jdbc=jdbc;}
 @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(broker,"/ws/chat").addInterceptors(new AuthHandshake()).setAllowedOriginPatterns("*");}
 private class AuthHandshake implements HandshakeInterceptor {
  @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
   try{
    String token=UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");if(token==null||redis.blacklisted(token))return false;
    String username=jwt.parse(token);boolean admin=jdbc.sql("SELECT EXISTS(SELECT 1 FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id JOIN sys_user u ON u.id=ur.user_id WHERE u.username=:u AND r.code='SUPER_ADMIN')").param("u",username).query(Boolean.class).single();
    if(!admin&&!redis.singleSessionValid(username,token))return false;attributes.put("username",username);return true;
   }catch(Exception e){return false;}
  }
  @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception){}
 }
}
