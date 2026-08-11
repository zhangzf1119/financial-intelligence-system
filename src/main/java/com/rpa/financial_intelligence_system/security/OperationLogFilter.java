package com.rpa.financial_intelligence_system.security;

import com.rpa.financial_intelligence_system.service.AuditLogService;
import jakarta.servlet.*; import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class OperationLogFilter extends OncePerRequestFilter {
 private final AuditLogService logs; public OperationLogFilter(AuditLogService logs){this.logs=logs;}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  long start=System.currentTimeMillis();String error=null;
  try{chain.doFilter(req,res);}catch(Exception e){error=e.getMessage();throw e;}finally{
   if(req.getRequestURI().startsWith("/api/")&&!req.getMethod().equals("GET")&&!req.getRequestURI().equals("/api/auth/login")&&!req.getRequestURI().startsWith("/api/ai/chat")){
    var a=SecurityContextHolder.getContext().getAuthentication();String user=a==null?"anonymous":a.getName();
    try{logs.operation(user,module(req.getRequestURI()),operation(req.getMethod()),req.getMethod(),req.getRequestURI(),ip(req),res.getStatus(),System.currentTimeMillis()-start,error);}catch(Exception ignored){}
   }
  }
 }
 private String module(String uri){if(uri.contains("/users"))return "用户管理";if(uri.contains("/roles"))return "角色管理";if(uri.contains("/menus"))return "菜单管理";if(uri.contains("/departments"))return "部门管理";if(uri.contains("/knowledge"))return "知识库";if(uri.contains("/settings"))return "模型设置";if(uri.contains("/logs"))return "日志管理";return "系统";}
 private String operation(String method){return switch(method){case "POST"->"新增";case "PUT","PATCH"->"修改";case "DELETE"->"删除";default->method;};}
 private String ip(HttpServletRequest r){String x=r.getHeader("X-Forwarded-For");return x==null?r.getRemoteAddr():x.split(",")[0].trim();}
}
