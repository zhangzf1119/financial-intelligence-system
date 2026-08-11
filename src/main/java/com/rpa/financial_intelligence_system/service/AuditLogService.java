package com.rpa.financial_intelligence_system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AuditLogService {
 private final JdbcClient jdbc; private final ObjectMapper mapper;
 public AuditLogService(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
 public void login(String username,String ip,String agent,boolean status,String message){jdbc.sql("INSERT INTO sys_login_log(username,ip_address,user_agent,status,message) VALUES(:u,:ip,:a,:s,:m)").param("u",clip(username,80)).param("ip",clip(ip,64)).param("a",clip(agent,500)).param("s",status).param("m",clip(message,300)).update();}
 public void operation(String username,String module,String operation,String method,String uri,String ip,int status,long duration,String error){jdbc.sql("INSERT INTO sys_operation_log(username,module,operation,method,request_uri,ip_address,status_code,success,duration_ms,error_message) VALUES(:u,:mo,:o,:me,:r,:ip,:sc,:s,:d,:e)").param("u",clip(username,80)).param("mo",clip(module,80)).param("o",operation).param("me",method).param("r",clip(uri,500)).param("ip",clip(ip,64)).param("sc",status).param("s",status<400).param("d",duration).param("e",clip(error,1000)).update();}
 public void chat(String username,String model,Object request,Object response,long duration,String status,String error,String ip){Map<String,Object> usage=response instanceof Map<?,?> m&&m.get("usage") instanceof Map<?,?> u?(Map<String,Object>)u:Map.of();Object content=response instanceof Map<?,?> m?m.get("content"):null;jdbc.sql("INSERT INTO ai_chat_log(username,model,request_content,response_content,prompt_tokens,completion_tokens,total_tokens,duration_ms,status,error_message,ip_address) VALUES(:u,:m,:q,:r,:p,:c,:t,:d,:s,:e,:ip)").param("u",clip(username,80)).param("m",clip(model,160)).param("q",json(request)).param("r",content==null?null:String.valueOf(content)).param("p",number(usage.get("prompt_tokens"))).param("c",number(usage.get("completion_tokens"))).param("t",number(usage.get("total_tokens"))).param("d",duration).param("s",status).param("e",clip(error,1000)).param("ip",clip(ip,64)).update();}
 private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){return String.valueOf(v);}}
 private int number(Object v){return v instanceof Number n?n.intValue():0;}
 private String clip(String s,int n){return s==null?null:s.substring(0,Math.min(n,s.length()));}
}
