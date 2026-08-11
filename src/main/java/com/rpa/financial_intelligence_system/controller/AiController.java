package com.rpa.financial_intelligence_system.controller;

import com.rpa.financial_intelligence_system.common.ApiResponse;
import com.rpa.financial_intelligence_system.service.AiService;
import com.rpa.financial_intelligence_system.service.AuditLogService;
import com.rpa.financial_intelligence_system.service.KnowledgeRetrievalService;
import com.rpa.financial_intelligence_system.service.ModelConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@Tag(name = "AI 智能中心")
@RestController
@RequestMapping("/api/ai")
public class AiController {
    /** 默认只把最相关的少量父块交给模型，避免上下文膨胀和来源卡片过多。 */
    private static final int RAG_TOP_K = 4;
    private final AiService ai;
    private final JdbcClient jdbc;
    private final ModelConfigService configs;
    private final AuditLogService logs;
    private final KnowledgeRetrievalService retrieval;

    public AiController(AiService ai, JdbcClient jdbc, ModelConfigService configs, AuditLogService logs,
                        KnowledgeRetrievalService retrieval) {
        this.ai = ai;
        this.jdbc = jdbc;
        this.configs = configs;
        this.logs = logs;
        this.retrieval = retrieval;
    }

    record ChatReq(@NotEmpty List<Map<String, String>> messages, String model, Double temperature,
                   Long conversationId, List<Long> knowledgeBaseIds) {}
    record ConversationReq(@Size(max = 160) String title) {}
    record KnowledgeReq(@NotBlank String title, @NotBlank String content) {}
    record ConfigReq(@NotBlank String name, @NotBlank String provider, @NotBlank String chatApiUrl,
                     String chatApiKey, @NotBlank String chatModel, @DecimalMin("0") @DecimalMax("2") Double temperature,
                     @Min(1) Integer maxTokens, @NotBlank String vectorBaseUrl, @NotBlank String vectorApiPath,
                     String vectorApiKey, @NotBlank String embeddingModel, @Min(1024) @Max(1024) Integer embeddingDimensions,
                     @Min(1) @Max(16) Integer vectorConcurrency, Boolean enabled) {}

    @Operation(summary = "模型对话")
    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('ai:chat')")
    ApiResponse<?> chat(@Valid @RequestBody ChatReq q, Authentication auth, HttpServletRequest req) {
        long start = System.currentTimeMillis();
        try {
            var hits = retrieve(q, uid(auth));
            var messages = withContext(q.messages(), hits);
            var out = new LinkedHashMap<>(ai.chat(messages, q.model(), q.temperature()));
            if (!hits.isEmpty()) out.put("sources", sourceCards(hits));
            logs.chat(auth.getName(), String.valueOf(out.get("model")), messages, out,
                    System.currentTimeMillis() - start, "SUCCESS", null, req.getRemoteAddr());
            return ApiResponse.ok(out);
        } catch (RuntimeException e) {
            logs.chat(auth.getName(), q.model(), q.messages(), null, System.currentTimeMillis() - start,
                    "FAILED", e.getMessage(), req.getRemoteAddr());
            throw e;
        }
    }

    @Operation(summary = "流式模型对话（SSE），可选挂载知识库")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('ai:chat')")
    SseEmitter chatStream(@Valid @RequestBody ChatReq q, Authentication auth, HttpServletRequest req) {
        var emitter = new SseEmitter(180_000L);
        String username = auth.getName();
        String ip = req.getRemoteAddr();
        long start = System.currentTimeMillis();
        long userId = uid(auth);
        long conversationId = q.conversationId() == null ? createConversation(userId, "新会话") : ownedConversation(q.conversationId(), userId);
        String userContent = lastUserContent(q.messages());
        Thread.ofVirtual().name("ai-stream-" + username).start(() -> {
            StringBuilder partial = new StringBuilder();
            try {
                storeMessage(conversationId, "user", userContent, null, null, "SUCCESS", null);
                updateConversationTitle(conversationId, userContent);
                var hits = retrieve(q, userId);
                var messages = withContext(q.messages(), hits);
                emitter.send(SseEmitter.event().name("conversation").data(Map.of("id", conversationId)));
                if (!hits.isEmpty()) emitter.send(SseEmitter.event().name("sources").data(sourceCards(hits)));
                var out = ai.chatStream(messages, q.model(), q.temperature(), chunk -> {
                    partial.append(chunk);
                    emitter.send(SseEmitter.event().name("delta").data(Map.of("content", chunk)));
                });
                if (!hits.isEmpty()) out.put("sources", sourceCards(hits));
                logs.chat(username, String.valueOf(out.get("model")), messages, out,
                        System.currentTimeMillis() - start, "SUCCESS", null, ip);
                storeMessage(conversationId, "assistant", String.valueOf(out.get("content")), String.valueOf(out.get("model")),
                        usage(out), "SUCCESS", null);
                jdbc.sql("UPDATE ai_conversation SET model=:m,updated_at=now() WHERE id=:id")
                        .param("m", out.get("model")).param("id", conversationId).update();
                emitter.send(SseEmitter.event().name("done").data(Map.of("conversationId", conversationId,
                        "model", out.get("model"), "usage", out.get("usage"))));
                emitter.complete();
            } catch (Exception e) {
                try {
                    logs.chat(username, q.model(), q.messages(), null, System.currentTimeMillis() - start,
                            "FAILED", e.getMessage(), ip);
                    storeMessage(conversationId, "assistant", partial.toString(), q.model(), null, "FAILED", e.getMessage());
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message",
                            Optional.ofNullable(e.getMessage()).orElse("模型流式响应失败"))));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    @Operation(summary = "查询我的模型会话记录")
    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('ai:chat')")
    ApiResponse<?> conversations(Authentication auth, @RequestParam(defaultValue = "") String keyword) {
        long userId = uid(auth);
        return ApiResponse.ok(jdbc.sql("""
                SELECT c.id,c.title,c.model,c.created_at,c.updated_at,COALESCE(x.message_count,0) message_count,x.last_content
                FROM ai_conversation c LEFT JOIN LATERAL(
                  SELECT count(*) message_count,(array_agg(m.content ORDER BY m.id DESC))[1] last_content
                  FROM ai_conversation_message m WHERE m.conversation_id=c.id
                ) x ON true WHERE c.user_id=:u AND (:k='' OR c.title ILIKE '%'||:k||'%' OR COALESCE(x.last_content,'') ILIKE '%'||:k||'%')
                ORDER BY c.updated_at DESC LIMIT 100
                """).param("u", userId).param("k", keyword.trim()).query().listOfRows());
    }

    @Operation(summary = "新建模型会话")
    @PostMapping("/conversations")
    @PreAuthorize("hasAuthority('ai:chat')")
    ApiResponse<?> createConversation(@RequestBody(required = false) ConversationReq q, Authentication auth) {
        String title = q == null || q.title() == null || q.title().isBlank() ? "新会话" : q.title().trim();
        return ApiResponse.ok(Map.of("id", createConversation(uid(auth), title), "title", title));
    }

    @Operation(summary = "查询模型会话消息")
    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('ai:chat')")
    ApiResponse<?> conversationMessages(@PathVariable long id, Authentication auth) {
        ownedConversation(id, uid(auth));
        return ApiResponse.ok(jdbc.sql("SELECT id,role,content,model,prompt_tokens,completion_tokens,total_tokens,status,error_message,created_at FROM ai_conversation_message WHERE conversation_id=:id ORDER BY id")
                .param("id", id).query().listOfRows());
    }

    @Operation(summary = "重命名模型会话")
    @PutMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('ai:chat')")
    ApiResponse<?> renameConversation(@PathVariable long id, @Valid @RequestBody ConversationReq q, Authentication auth) {
        ownedConversation(id, uid(auth));
        if (q.title() == null || q.title().isBlank()) throw new IllegalArgumentException("会话标题不能为空");
        jdbc.sql("UPDATE ai_conversation SET title=:t,updated_at=now() WHERE id=:id").param("t", q.title().trim()).param("id", id).update();
        return ApiResponse.ok();
    }

    @Operation(summary = "删除模型会话及消息")
    @DeleteMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('ai:chat')")
    ApiResponse<?> deleteConversation(@PathVariable long id, Authentication auth) {
        ownedConversation(id, uid(auth));
        jdbc.sql("DELETE FROM ai_conversation WHERE id=:id").param("id", id).update();
        return ApiResponse.ok();
    }

    @GetMapping("/knowledge")
    @PreAuthorize("hasAuthority('ai:knowledge:list')")
    ApiResponse<?> list() { return ApiResponse.ok(jdbc.sql("SELECT id,title,content,created_at,updated_at FROM ai_knowledge ORDER BY id DESC").query().listOfRows()); }

    @PostMapping("/knowledge")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    ApiResponse<?> add(@Valid @RequestBody KnowledgeReq q, Authentication auth) {
        var v = toVector(ai.embedding(q.content()));
        long userId = uid(auth);
        long id = jdbc.sql("INSERT INTO ai_knowledge(title,content,embedding,created_by) VALUES(:t,:c,cast(:e as vector),:u) RETURNING id")
                .param("t", q.title()).param("c", q.content()).param("e", v).param("u", userId).query(Long.class).single();
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/knowledge/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    ApiResponse<?> edit(@PathVariable long id, @Valid @RequestBody KnowledgeReq q) {
        jdbc.sql("UPDATE ai_knowledge SET title=:t,content=:c,embedding=cast(:e as vector),updated_at=now() WHERE id=:id")
                .param("t", q.title()).param("c", q.content()).param("e", toVector(ai.embedding(q.content()))).param("id", id).update();
        return ApiResponse.ok();
    }

    @DeleteMapping("/knowledge/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    ApiResponse<?> del(@PathVariable long id) { jdbc.sql("DELETE FROM ai_knowledge WHERE id=:id").param("id", id).update(); return ApiResponse.ok(); }

    @GetMapping("/knowledge/search")
    ApiResponse<?> search(@RequestParam String q, @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(jdbc.sql("SELECT id,title,content,1-(embedding <=> cast(:e as vector)) similarity FROM ai_knowledge WHERE embedding IS NOT NULL ORDER BY embedding <=> cast(:e as vector) LIMIT :n")
                .param("e", toVector(ai.embedding(q))).param("n", Math.min(Math.max(limit, 1), 20)).query().listOfRows());
    }

    @GetMapping("/settings") @PreAuthorize("hasAuthority('ai:settings')") ApiResponse<?> settings() { return ApiResponse.ok(configs.list()); }
    @PostMapping("/settings") @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')") ApiResponse<?> addConfig(@Valid @RequestBody ConfigReq q) { return ApiResponse.ok(Map.of("id", configs.save(null, q.name(), q.provider(), q.chatApiUrl(), q.chatApiKey(), q.chatModel(), q.temperature(), q.maxTokens(), q.vectorBaseUrl(), q.vectorApiPath(), q.vectorApiKey(), q.embeddingModel(), q.embeddingDimensions(), q.vectorConcurrency(), q.enabled()))); }
    @PutMapping("/settings/{id}") @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')") ApiResponse<?> editConfig(@PathVariable long id, @Valid @RequestBody ConfigReq q) { configs.save(id, q.name(), q.provider(), q.chatApiUrl(), q.chatApiKey(), q.chatModel(), q.temperature(), q.maxTokens(), q.vectorBaseUrl(), q.vectorApiPath(), q.vectorApiKey(), q.embeddingModel(), q.embeddingDimensions(), q.vectorConcurrency(), q.enabled()); return ApiResponse.ok(); }
    @DeleteMapping("/settings/{id}") @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')") ApiResponse<?> delConfig(@PathVariable long id) { configs.delete(id); return ApiResponse.ok(); }
    @PostMapping("/settings/test/chat") @PreAuthorize("hasAuthority('ai:settings')") ApiResponse<?> testChat() { return ApiResponse.ok(ai.testChat()); }
    @PostMapping("/settings/test/vector") @PreAuthorize("hasAuthority('ai:settings')") ApiResponse<?> testVector() { return ApiResponse.ok(ai.testVector()); }

    private List<Map<String, Object>> retrieve(ChatReq q, long userId) {
        return retrieval.search(q.knowledgeBaseIds() == null ? List.of() : q.knowledgeBaseIds(), userId, lastUserContent(q.messages()), RAG_TOP_K);
    }

    private List<Map<String, String>> withContext(List<Map<String, String>> messages, List<Map<String, Object>> hits) {
        if (hits.isEmpty()) return messages;
        var result = new ArrayList<Map<String, String>>();
        result.add(Map.of("role", "system", "content", contextPrompt(hits)));
        result.addAll(messages);
        return result;
    }

    private String contextPrompt(List<Map<String, Object>> hits) {
        var context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            Object page = hit.get("pageNumber");
            context.append("[").append(i + 1).append("] [文档: ").append(hit.get("documentName"));
            if (page != null) context.append("，第 ").append(page).append(" 页");
            context.append("]\n").append(hit.get("content")).append("\n\n");
        }
        return "你是一个严谨的企业知识库助手。请严格根据以下给出的【参考文档】回答用户的【问题】。\n" +
                "【参考文档】:\n" + context +
                "【回答准则】:\n" +
                "1. 仅基于上述【参考文档】提供的内容回答，禁止引入外部常识或进行未经根据的推测。\n" +
                "2. 若参考文档中无相关信息，请明确回答“根据当前知识库，无法回答该问题。”\n" +
                "3. 请在引用信息的句子末尾标注引用编号（如 [1]、[2]），不要重复输出完整文件名。";
    }

    private List<Map<String, Object>> sourceCards(List<Map<String, Object>> hits) {
        return hits.stream().<Map<String, Object>>map(hit -> {
            var card = new LinkedHashMap<String, Object>();
            card.put("sourceIndex", hits.indexOf(hit) + 1); card.put("chunkId", hit.get("chunkId")); card.put("documentId", hit.get("documentId"));
            card.put("documentName", hit.get("documentName")); card.put("knowledgeBaseId", hit.get("knowledgeBaseId"));
            card.put("knowledgeBaseName", hit.get("knowledgeBaseName")); card.put("pageNumber", hit.get("pageNumber"));
            card.put("content", hit.get("content")); card.put("score", hit.get("fusedScore"));
            return card;
        }).toList();
    }

    private long uid(Authentication auth) { return jdbc.sql("SELECT id FROM sys_user WHERE username=:u").param("u", auth.getName()).query(Long.class).single(); }
    private long createConversation(long userId, String title) { return jdbc.sql("INSERT INTO ai_conversation(user_id,title) VALUES(:u,:t) RETURNING id").param("u", userId).param("t", title).query(Long.class).single(); }
    private long ownedConversation(long id, long userId) { return jdbc.sql("SELECT id FROM ai_conversation WHERE id=:id AND user_id=:u").param("id", id).param("u", userId).query(Long.class).optional().orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问")); }
    private String lastUserContent(List<Map<String, String>> messages) { for (int i = messages.size() - 1; i >= 0; i--) { var message = messages.get(i); if ("user".equals(message.get("role"))) return Optional.ofNullable(message.get("content")).orElse(""); } throw new IllegalArgumentException("缺少用户消息"); }
    private void updateConversationTitle(long id, String content) { String title = content.replaceAll("\\s+", " ").trim(); if (title.length() > 36) title = title.substring(0, 36) + "…"; jdbc.sql("UPDATE ai_conversation SET title=CASE WHEN title='新会话' THEN :t ELSE title END,updated_at=now() WHERE id=:id").param("t", title.isBlank() ? "新会话" : title).param("id", id).update(); }
    @SuppressWarnings("unchecked") private Map<String, Object> usage(Map<String, Object> out) { return out.get("usage") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private void storeMessage(long conversationId, String role, String content, String model, Map<String, Object> usage, String status, String error) { Map<String, Object> u = usage == null ? Map.of() : usage; jdbc.sql("INSERT INTO ai_conversation_message(conversation_id,role,content,model,prompt_tokens,completion_tokens,total_tokens,status,error_message) VALUES(:c,:r,:x,:m,:p,:o,:t,:s,:e)").param("c", conversationId).param("r", role).param("x", Optional.ofNullable(content).orElse("")).param("m", model).param("p", number(u.get("prompt_tokens"))).param("o", number(u.get("completion_tokens"))).param("t", number(u.get("total_tokens"))).param("s", status).param("e", error == null ? null : error.substring(0, Math.min(1000, error.length()))).update(); }
    private Integer number(Object value) { return value instanceof Number n ? n.intValue() : null; }
    private String toVector(List<Double> v) { return v.toString(); }
}
