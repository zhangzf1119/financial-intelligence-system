package com.rpa.financial_intelligence_system.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.time.Duration;import java.util.HexFormat;import java.util.Objects;import java.util.Set;

@Service
public class RedisSecurityService {
 private final StringRedisTemplate redis; public RedisSecurityService(StringRedisTemplate redis){this.redis=redis;}
 public boolean loginAllowed(String username,String ip){String key="fis:login:fail:"+username+":"+ip;String value=redis.opsForValue().get(key);return value==null||Integer.parseInt(value)<5;}
 public void loginFailed(String username,String ip){String key="fis:login:fail:"+username+":"+ip;Long count=redis.opsForValue().increment(key);if(count!=null&&count==1)redis.expire(key,Duration.ofMinutes(10));}
 public void loginSucceeded(String username,String ip){redis.delete("fis:login:fail:"+username+":"+ip);}
 public void blacklist(String token,Duration ttl){redis.opsForValue().set("fis:jwt:blacklist:"+hash(token),"1",ttl);}
 public boolean blacklisted(String token){return Boolean.TRUE.equals(redis.hasKey("fis:jwt:blacklist:"+hash(token)));}
 public void bindSingleSession(String username,String token,Duration ttl){redis.opsForValue().set("fis:session:single:"+username,hash(token),ttl);}
 public boolean singleSessionValid(String username,String token){String active=redis.opsForValue().get("fis:session:single:"+username);return active!=null&&active.equals(hash(token));}
 public void clearSingleSession(String username,String token){String key="fis:session:single:"+username;if(Objects.equals(redis.opsForValue().get(key),hash(token)))redis.delete(key);}
 public void registerSession(String username,String token,Duration ttl){String key="fis:session:tokens:"+username;redis.opsForSet().add(key,hash(token));redis.expire(key,ttl);}
 public void unregisterSession(String username,String token){redis.opsForSet().remove("fis:session:tokens:"+username,hash(token));}
 public boolean online(String username){Long size=redis.opsForSet().size("fis:session:tokens:"+username);return size!=null&&size>0;}
 public long forceLogout(String username){String key="fis:session:tokens:"+username;Set<String> tokens=redis.opsForSet().members(key);if(tokens==null)tokens=Set.of();tokens.forEach(x->redis.opsForValue().set("fis:jwt:blacklist:"+x,"1",Duration.ofDays(7)));redis.delete(key);redis.delete("fis:session:single:"+username);return tokens.size();}
 private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
