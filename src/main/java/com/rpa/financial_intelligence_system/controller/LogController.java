package com.rpa.financial_intelligence_system.controller;

import com.rpa.financial_intelligence_system.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation; import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Tag(name="日志管理",description="登录日志、操作审计日志和模型对话日志") @RestController @RequestMapping("/api/logs")
@PreAuthorize("hasAuthority('system:log:list')")
public class LogController {
 private final JdbcClient jdbc; public LogController(JdbcClient jdbc){this.jdbc=jdbc;}
 @Operation(summary="分页查询登录日志") @GetMapping("/login") ApiResponse<?> login(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return query("sys_login_log","login_time","username,ip_address,message",keyword,page,size);}
 @Operation(summary="分页查询操作日志") @GetMapping("/operation") ApiResponse<?> operation(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return query("sys_operation_log","operation_time","username,module,operation,request_uri",keyword,page,size);}
 @Operation(summary="分页查询模型对话日志") @GetMapping("/chat") ApiResponse<?> chat(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return query("ai_chat_log","created_at","username,model,request_content,response_content",keyword,page,size);}
 @Operation(summary="删除单条日志") @DeleteMapping("/{type}/{id}") @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')") ApiResponse<?> delete(@PathVariable String type,@PathVariable long id){jdbc.sql("DELETE FROM "+table(type)+" WHERE id=:id").param("id",id).update();return ApiResponse.ok();}
 @Operation(summary="清理指定天数以前的日志") @DeleteMapping("/{type}") @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')") ApiResponse<?> clean(@PathVariable String type,@RequestParam(defaultValue="90")int beforeDays){String t=table(type),time=switch(type){case"login"->"login_time";case"operation"->"operation_time";default->"created_at";};int count=jdbc.sql("DELETE FROM "+t+" WHERE "+time+" < now() - (:days * interval '1 day')").param("days",Math.max(1,beforeDays)).update();return ApiResponse.ok(Map.of("deleted",count));}
 private ApiResponse<?> query(String table,String time,String columns,String keyword,int page,int size){int limit=Math.min(Math.max(size,1),100),offset=(Math.max(page,1)-1)*limit;String[] cs=columns.split(",");String where=keyword.isBlank()?"":" WHERE "+String.join(" OR ",Arrays.stream(cs).map(c->"LOWER(COALESCE("+c+"::text,'')) LIKE :k").toList());var q=jdbc.sql("SELECT * FROM "+table+where+" ORDER BY "+time+" DESC LIMIT :n OFFSET :o");var c=jdbc.sql("SELECT count(*) FROM "+table+where);if(!keyword.isBlank()){q.param("k","%"+keyword.toLowerCase()+"%");c.param("k","%"+keyword.toLowerCase()+"%");}return ApiResponse.ok(Map.of("records",q.param("n",limit).param("o",offset).query().listOfRows(),"total",c.query(Long.class).single(),"page",Math.max(page,1),"size",limit));}
 private String table(String type){return switch(type){case"login"->"sys_login_log";case"operation"->"sys_operation_log";case"chat"->"ai_chat_log";default->throw new IllegalArgumentException("不支持的日志类型");};}
}
