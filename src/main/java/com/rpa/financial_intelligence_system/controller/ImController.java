package com.rpa.financial_intelligence_system.controller;

import com.rpa.financial_intelligence_system.common.ApiResponse;
import com.rpa.financial_intelligence_system.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Tag(name="即时通讯",description="系统用户单聊、群聊、文件消息、未读与实时推送")
@RestController @RequestMapping("/api/im")
public class ImController {
 private final JdbcClient jdbc; private final RedisSecurityService redis; private final ImSocketBroker socket; private final StorageService storage; private final OfficePreviewService officePreview;
 public ImController(JdbcClient jdbc,RedisSecurityService redis,ImSocketBroker socket,StorageService storage,OfficePreviewService officePreview){this.jdbc=jdbc;this.redis=redis;this.socket=socket;this.storage=storage;this.officePreview=officePreview;}
 record TextReq(@NotBlank @Size(max=4000)String content,Long replyToId){}
 record GroupReq(@NotBlank @Size(max=120)String name,@NotEmpty List<Long> memberIds){}
 record MembersReq(@NotEmpty List<Long> userIds){}
 record RenameReq(@NotBlank @Size(max=120)String name){}
 record SettingReq(Boolean pinned,Boolean muted){}

 @Operation(summary="查询可聊天用户") @GetMapping("/users") ApiResponse<?> users(Authentication a,@RequestParam(defaultValue="")String keyword){long me=uid(a);var rows=jdbc.sql("SELECT u.id,u.username,u.nickname,u.email,d.name department_name FROM sys_user u LEFT JOIN sys_department d ON d.id=u.department_id WHERE u.status AND u.id<>:me AND (:k='' OR u.username ILIKE '%'||:k||'%' OR u.nickname ILIKE '%'||:k||'%' OR d.name ILIKE '%'||:k||'%') ORDER BY u.nickname LIMIT 100").param("me",me).param("k",keyword.trim()).query().listOfRows();rows.forEach(r->{String username=(String)r.get("username");r.put("online",redis.online(username)||socket.connected(username));});return ApiResponse.ok(rows);}

 @Operation(summary="查询我的会话列表") @GetMapping("/conversations") ApiResponse<?> conversations(Authentication a){long me=uid(a);String sql="""
  SELECT c.id,c.type,c.name,c.owner_id,c.updated_at,m.pinned,m.muted,
   CASE WHEN c.type='DIRECT' THEN peer.nickname ELSE c.name END display_name,
   CASE WHEN c.type='DIRECT' THEN peer.username ELSE NULL END peer_username,
   CASE WHEN c.type='DIRECT' THEN peer.id ELSE NULL END peer_user_id,
   CASE WHEN c.type='DIRECT' THEN peer.department_name ELSE member_count::text||'人群聊' END subtitle,
   lm.id last_message_id,lm.type last_message_type,lm.content last_message_content,lm.created_at last_message_at,
   lm.sender_id last_sender_id,lm.sender_name last_sender_name,
   COALESCE((SELECT bool_or(om.last_read_message_id>=COALESCE(lm.id,0)) FROM sys_im_member om WHERE om.conversation_id=c.id AND om.user_id<>:me),false) read_by_other,
   (SELECT count(*) FROM sys_im_message x WHERE x.conversation_id=c.id AND x.id>m.last_read_message_id AND x.sender_id<>:me AND x.recalled_at IS NULL) unread_count,
   member_count
  FROM sys_im_member m JOIN sys_im_conversation c ON c.id=m.conversation_id
  LEFT JOIN LATERAL (SELECT u.id,u.username,u.nickname,d.name department_name FROM sys_im_member pm JOIN sys_user u ON u.id=pm.user_id LEFT JOIN sys_department d ON d.id=u.department_id WHERE pm.conversation_id=c.id AND pm.user_id<>:me LIMIT 1) peer ON true
  LEFT JOIN LATERAL (SELECT msg.id,msg.type,msg.content,msg.created_at,msg.sender_id,u.nickname sender_name FROM sys_im_message msg LEFT JOIN sys_user u ON u.id=msg.sender_id WHERE msg.conversation_id=c.id ORDER BY msg.id DESC LIMIT 1) lm ON true
  LEFT JOIN LATERAL (SELECT count(*) member_count FROM sys_im_member cm WHERE cm.conversation_id=c.id) mc ON true
  WHERE m.user_id=:me ORDER BY m.pinned DESC,c.updated_at DESC
  """;var rows=jdbc.sql(sql).param("me",me).query().listOfRows();rows.forEach(r->{if("DIRECT".equals(r.get("type"))){String username=(String)r.get("peer_username");r.put("online",username!=null&&(redis.online(username)||socket.connected(username)));}else r.put("online",true);if("IMAGE".equals(r.get("last_message_type")))r.put("last_message_content","[图片]");if("FILE".equals(r.get("last_message_type")))r.put("last_message_content","[文件]");if(r.get("last_message_content")==null)r.put("last_message_content","暂无消息");});return ApiResponse.ok(rows);}

 @Operation(summary="查询未读消息总数") @GetMapping("/unread-count") ApiResponse<?> unread(Authentication a){long me=uid(a);long count=jdbc.sql("SELECT count(*) FROM sys_im_member m JOIN sys_im_message x ON x.conversation_id=m.conversation_id WHERE m.user_id=:me AND x.id>m.last_read_message_id AND x.sender_id<>:me AND x.recalled_at IS NULL").param("me",me).query(Long.class).single();return ApiResponse.ok(Map.of("count",count));}

 @Operation(summary="发起或打开单聊") @PostMapping("/conversations/direct/{userId}") @Transactional ApiResponse<?> direct(@PathVariable long userId,Authentication a){long me=uid(a);if(me==userId)throw new IllegalArgumentException("不能与自己发起单聊");ensureUser(userId);String key=Math.min(me,userId)+":"+Math.max(me,userId);long id=jdbc.sql("INSERT INTO sys_im_conversation(type,direct_key,owner_id) VALUES('DIRECT',:k,:me) ON CONFLICT(direct_key) DO UPDATE SET direct_key=excluded.direct_key RETURNING id").param("k",key).param("me",me).query(Long.class).single();jdbc.sql("INSERT INTO sys_im_member(conversation_id,user_id,member_role) VALUES(:c,:u,'MEMBER') ON CONFLICT DO NOTHING").param("c",id).param("u",me).update();jdbc.sql("INSERT INTO sys_im_member(conversation_id,user_id,member_role) VALUES(:c,:u,'MEMBER') ON CONFLICT DO NOTHING").param("c",id).param("u",userId).update();return ApiResponse.ok(Map.of("id",id));}

 @Operation(summary="创建群聊") @PostMapping("/conversations/groups") @Transactional ApiResponse<?> group(@Valid @RequestBody GroupReq q,Authentication a){long me=uid(a);long id=jdbc.sql("INSERT INTO sys_im_conversation(type,name,owner_id) VALUES('GROUP',:n,:u) RETURNING id").param("n",q.name().trim()).param("u",me).query(Long.class).single();jdbc.sql("INSERT INTO sys_im_member(conversation_id,user_id,member_role) VALUES(:c,:u,'OWNER')").param("c",id).param("u",me).update();q.memberIds().stream().distinct().filter(x->x!=me).forEach(user->{ensureUser(user);jdbc.sql("INSERT INTO sys_im_member(conversation_id,user_id) VALUES(:c,:u) ON CONFLICT DO NOTHING").param("c",id).param("u",user).update();});insertMessage(id,me,"SYSTEM",name(me)+"创建了群聊",null,null);broadcast(id,"CONVERSATION_UPDATED",Map.of("conversationId",id));return ApiResponse.ok(Map.of("id",id));}

 @Operation(summary="查询会话消息记录") @GetMapping("/conversations/{id}/messages") ApiResponse<?> messages(@PathVariable long id,Authentication a,@RequestParam(required=false)Long beforeId,@RequestParam(defaultValue="40")@Min(1)@Max(100)int size){long me=uid(a);member(id,me);String extra=beforeId==null?"":" AND msg.id<:before";var spec=jdbc.sql("SELECT msg.id,msg.conversation_id,msg.sender_id,u.username sender_username,u.nickname sender_name,msg.type,msg.content,msg.file_id,f.original_name file_name,f.content_type,f.size_bytes,msg.reply_to_id,r.content reply_content,msg.recalled_at,msg.created_at FROM sys_im_message msg LEFT JOIN sys_user u ON u.id=msg.sender_id LEFT JOIN sys_file f ON f.id=msg.file_id LEFT JOIN sys_im_message r ON r.id=msg.reply_to_id WHERE msg.conversation_id=:c"+extra+" ORDER BY msg.id DESC LIMIT :size").param("c",id).param("size",size);if(beforeId!=null)spec.param("before",beforeId);var rows=spec.query().listOfRows();Collections.reverse(rows);return ApiResponse.ok(rows);}

 @Operation(summary="发送文本消息") @PostMapping("/conversations/{id}/messages") @Transactional ApiResponse<?> send(@PathVariable long id,@Valid @RequestBody TextReq q,Authentication a){long me=uid(a);member(id,me);if(q.replyToId()!=null&&jdbc.sql("SELECT count(*) FROM sys_im_message WHERE id=:r AND conversation_id=:c").param("r",q.replyToId()).param("c",id).query(Long.class).single()==0)throw new IllegalArgumentException("回复的消息不存在");long messageId=insertMessage(id,me,"TEXT",q.content().trim(),null,q.replyToId());var message=message(messageId);broadcast(id,"MESSAGE",message);return ApiResponse.ok(message);}

 @Operation(summary="发送图片或文件") @PostMapping(value="/conversations/{id}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional ApiResponse<?> sendFile(@PathVariable long id,@RequestPart("file")MultipartFile file,Authentication a)throws Exception{long me=uid(a);member(id,me);var saved=storage.upload(file,me);long fileId=((Number)saved.get("id")).longValue();String type=Optional.ofNullable(file.getContentType()).orElse("").startsWith("image/")?"IMAGE":"FILE";long messageId=insertMessage(id,me,type,(String)saved.get("name"),fileId,null);var message=message(messageId);broadcast(id,"MESSAGE",message);return ApiResponse.ok(message);}

 @Operation(summary="在线预览或下载聊天文件") @GetMapping("/files/{fileId}/download") ResponseEntity<Resource> download(@PathVariable long fileId,Authentication a)throws Exception{var f=chatFile(fileId,uid(a));String filename=URLEncoder.encode((String)f.get("original_name"),StandardCharsets.UTF_8).replace("+","%20");return ResponseEntity.ok().contentType(safeType((String)f.get("content_type"))).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename*=UTF-8''"+filename).header("X-Content-Type-Options","nosniff").contentLength(((Number)f.get("size_bytes")).longValue()).body(storage.download(f));}

 @Operation(summary="将聊天中的 Word、Excel、PPT 转为 PDF 预览") @GetMapping(value="/files/{fileId}/preview-pdf",produces=MediaType.APPLICATION_PDF_VALUE) ResponseEntity<Resource> previewPdf(@PathVariable long fileId,Authentication a)throws Exception{var f=chatFile(fileId,uid(a));if(!officePreview.supports((String)f.get("original_name")))throw new IllegalArgumentException("该文件类型无需 Office 转换");var pdf=officePreview.pdf(f);return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"inline").header("X-Content-Type-Options","nosniff").contentLength(pdf.contentLength()).body(pdf);}

 @Operation(summary="将会话标记为已读") @PutMapping("/conversations/{id}/read") ApiResponse<?> read(@PathVariable long id,Authentication a){long me=uid(a);member(id,me);jdbc.sql("UPDATE sys_im_member SET last_read_message_id=GREATEST(last_read_message_id,COALESCE((SELECT max(id) FROM sys_im_message WHERE conversation_id=:c),0)) WHERE conversation_id=:c AND user_id=:u").param("c",id).param("u",me).update();broadcast(id,"READ",Map.of("conversationId",id,"userId",me));return ApiResponse.ok();}

 @Operation(summary="发送正在输入状态") @PostMapping("/conversations/{id}/typing") ApiResponse<?> typing(@PathVariable long id,Authentication a){long me=uid(a);member(id,me);broadcast(id,"TYPING",Map.of("conversationId",id,"userId",me,"name",name(me)));return ApiResponse.ok();}

 @Operation(summary="撤回本人消息") @DeleteMapping("/messages/{messageId}") ApiResponse<?> recall(@PathVariable long messageId,Authentication a){long me=uid(a);var rows=jdbc.sql("SELECT id,conversation_id FROM sys_im_message WHERE id=:id AND sender_id=:u AND recalled_at IS NULL AND created_at>now()-interval '2 minutes'").param("id",messageId).param("u",me).query().listOfRows();if(rows.isEmpty())throw new IllegalArgumentException("只能撤回 2 分钟内自己发送的消息");long conversationId=((Number)rows.getFirst().get("conversation_id")).longValue();jdbc.sql("UPDATE sys_im_message SET recalled_at=now(),content=NULL WHERE id=:id").param("id",messageId).update();broadcast(conversationId,"RECALLED",Map.of("conversationId",conversationId,"messageId",messageId));return ApiResponse.ok();}

 @Operation(summary="搜索聊天记录") @GetMapping("/messages/search") ApiResponse<?> search(Authentication a,@RequestParam String keyword){long me=uid(a);if(keyword.isBlank())return ApiResponse.ok(List.of());return ApiResponse.ok(jdbc.sql("SELECT msg.id,msg.conversation_id,msg.content,msg.created_at,u.nickname sender_name,c.type,c.name FROM sys_im_message msg JOIN sys_im_member m ON m.conversation_id=msg.conversation_id LEFT JOIN sys_user u ON u.id=msg.sender_id JOIN sys_im_conversation c ON c.id=msg.conversation_id WHERE m.user_id=:u AND msg.recalled_at IS NULL AND msg.content ILIKE '%'||:k||'%' ORDER BY msg.id DESC LIMIT 50").param("u",me).param("k",keyword.trim()).query().listOfRows());}

 @Operation(summary="查询群聊成员") @GetMapping("/conversations/{id}/members") ApiResponse<?> members(@PathVariable long id,Authentication a){long me=uid(a);member(id,me);return ApiResponse.ok(jdbc.sql("SELECT u.id,u.username,u.nickname,d.name department_name,m.member_role,m.joined_at FROM sys_im_member m JOIN sys_user u ON u.id=m.user_id LEFT JOIN sys_department d ON d.id=u.department_id WHERE m.conversation_id=:c ORDER BY CASE m.member_role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END,u.nickname").param("c",id).query().listOfRows());}

 @Operation(summary="邀请群聊成员") @PostMapping("/conversations/{id}/members") @Transactional ApiResponse<?> addMembers(@PathVariable long id,@Valid @RequestBody MembersReq q,Authentication a){long me=uid(a);requireGroupAdmin(id,me);q.userIds().stream().distinct().forEach(user->{ensureUser(user);jdbc.sql("INSERT INTO sys_im_member(conversation_id,user_id) VALUES(:c,:u) ON CONFLICT DO NOTHING").param("c",id).param("u",user).update();});touch(id);insertMessage(id,me,"SYSTEM",name(me)+"邀请新成员加入群聊",null,null);broadcast(id,"CONVERSATION_UPDATED",Map.of("conversationId",id));return ApiResponse.ok();}

 @Operation(summary="移除群聊成员") @DeleteMapping("/conversations/{id}/members/{userId}") ApiResponse<?> removeMember(@PathVariable long id,@PathVariable long userId,Authentication a){long me=uid(a);if(me!=userId)requireGroupAdmin(id,me);String role=jdbc.sql("SELECT member_role FROM sys_im_member WHERE conversation_id=:c AND user_id=:u").param("c",id).param("u",userId).query(String.class).optional().orElseThrow(()->new IllegalArgumentException("群成员不存在"));if("OWNER".equals(role))throw new IllegalArgumentException("群主不能退出或被移除");jdbc.sql("DELETE FROM sys_im_member WHERE conversation_id=:c AND user_id=:u").param("c",id).param("u",userId).update();broadcast(id,"CONVERSATION_UPDATED",Map.of("conversationId",id));return ApiResponse.ok();}

 @Operation(summary="修改群聊名称") @PutMapping("/conversations/{id}/name") ApiResponse<?> rename(@PathVariable long id,@Valid @RequestBody RenameReq q,Authentication a){long me=uid(a);requireGroupAdmin(id,me);jdbc.sql("UPDATE sys_im_conversation SET name=:n,updated_at=now() WHERE id=:c AND type='GROUP'").param("n",q.name().trim()).param("c",id).update();broadcast(id,"CONVERSATION_UPDATED",Map.of("conversationId",id));return ApiResponse.ok();}

 @Operation(summary="设置置顶和免打扰") @PutMapping("/conversations/{id}/settings") ApiResponse<?> setting(@PathVariable long id,@RequestBody SettingReq q,Authentication a){long me=uid(a);member(id,me);jdbc.sql("UPDATE sys_im_member SET pinned=COALESCE(:p,pinned),muted=COALESCE(:m,muted) WHERE conversation_id=:c AND user_id=:u").param("p",q.pinned()).param("m",q.muted()).param("c",id).param("u",me).update();return ApiResponse.ok();}

 private long insertMessage(long conversationId,long senderId,String type,String content,Long fileId,Long replyTo){long id=jdbc.sql("INSERT INTO sys_im_message(conversation_id,sender_id,type,content,file_id,reply_to_id) VALUES(:c,:u,:t,:x,:f,:r) RETURNING id").param("c",conversationId).param("u",senderId).param("t",type).param("x",content).param("f",fileId).param("r",replyTo).query(Long.class).single();touch(conversationId);return id;}
 private Map<String,Object> message(long id){return jdbc.sql("SELECT msg.id,msg.conversation_id,msg.sender_id,u.username sender_username,u.nickname sender_name,msg.type,msg.content,msg.file_id,f.original_name file_name,f.content_type,f.size_bytes,msg.reply_to_id,r.content reply_content,msg.recalled_at,msg.created_at FROM sys_im_message msg LEFT JOIN sys_user u ON u.id=msg.sender_id LEFT JOIN sys_file f ON f.id=msg.file_id LEFT JOIN sys_im_message r ON r.id=msg.reply_to_id WHERE msg.id=:id").param("id",id).query().singleRow();}
 private void broadcast(long conversationId,String event,Object data){List<String> users=jdbc.sql("SELECT u.username FROM sys_im_member m JOIN sys_user u ON u.id=m.user_id WHERE m.conversation_id=:c").param("c",conversationId).query(String.class).list();var payload=new LinkedHashMap<String,Object>();payload.put("event",event);payload.put("conversationId",conversationId);payload.put("data",data);socket.send(users,payload);}
 private void member(long conversationId,long userId){if(jdbc.sql("SELECT count(*) FROM sys_im_member WHERE conversation_id=:c AND user_id=:u").param("c",conversationId).param("u",userId).query(Long.class).single()==0)throw new IllegalArgumentException("无权访问该会话");}
 private void requireGroupAdmin(long conversationId,long userId){String role=jdbc.sql("SELECT m.member_role FROM sys_im_member m JOIN sys_im_conversation c ON c.id=m.conversation_id WHERE m.conversation_id=:c AND m.user_id=:u AND c.type='GROUP'").param("c",conversationId).param("u",userId).query(String.class).optional().orElseThrow(()->new IllegalArgumentException("无权管理该群聊"));if(!role.equals("OWNER")&&!role.equals("ADMIN"))throw new IllegalArgumentException("仅群主或管理员可执行该操作");}
 private void touch(long id){jdbc.sql("UPDATE sys_im_conversation SET updated_at=now() WHERE id=:id").param("id",id).update();}
 private long uid(Authentication a){return jdbc.sql("SELECT id FROM sys_user WHERE username=:u").param("u",a.getName()).query(Long.class).single();}
 private String name(long id){return jdbc.sql("SELECT nickname FROM sys_user WHERE id=:id").param("id",id).query(String.class).single();}
 private void ensureUser(long id){if(jdbc.sql("SELECT count(*) FROM sys_user WHERE id=:id AND status").param("id",id).query(Long.class).single()==0)throw new IllegalArgumentException("用户不存在或已停用");}
 private Map<String,Object> chatFile(long fileId,long userId){var rows=jdbc.sql("SELECT DISTINCT f.* FROM sys_file f JOIN sys_im_message msg ON msg.file_id=f.id JOIN sys_im_member m ON m.conversation_id=msg.conversation_id WHERE f.id=:f AND m.user_id=:u AND f.status='ACTIVE'").param("f",fileId).param("u",userId).query().listOfRows();if(rows.isEmpty())throw new IllegalArgumentException("文件不存在或无权访问");return rows.getFirst();}
 private MediaType safeType(String value){try{return MediaType.parseMediaType(Optional.ofNullable(value).filter(v->!v.isBlank()).orElse("application/octet-stream"));}catch(Exception ignored){return MediaType.APPLICATION_OCTET_STREAM;}}
}
