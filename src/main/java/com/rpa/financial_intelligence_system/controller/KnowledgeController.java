package com.rpa.financial_intelligence_system.controller;

import com.rpa.financial_intelligence_system.common.ApiResponse;
import com.rpa.financial_intelligence_system.service.KnowledgeDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "RAG 知识库", description = "知识库集合、文档解析、父子切片和状态管理")
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeDocumentService knowledge;
    private final JdbcClient jdbc;

    public KnowledgeController(KnowledgeDocumentService knowledge, JdbcClient jdbc) {
        this.knowledge = knowledge;
        this.jdbc = jdbc;
    }

    record BaseReq(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description, String permissionType) {}

    @Operation(summary = "查询可访问的知识库")
    @GetMapping("/bases")
    @PreAuthorize("hasAuthority('ai:knowledge:list')")
    ApiResponse<?> bases(Authentication auth) { return ApiResponse.ok(knowledge.bases(uid(auth))); }

    @Operation(summary = "新建知识库")
    @PostMapping("/bases")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    ApiResponse<?> createBase(@Valid @RequestBody BaseReq req, Authentication auth) {
        return ApiResponse.ok(java.util.Map.of("id", knowledge.createBase(req.name(), req.description(), req.permissionType(), uid(auth))));
    }

    @Operation(summary = "修改知识库")
    @PutMapping("/bases/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    ApiResponse<?> updateBase(@PathVariable long id, @Valid @RequestBody BaseReq req, Authentication auth) {
        knowledge.updateBase(id, req.name(), req.description(), req.permissionType(), uid(auth));
        return ApiResponse.ok();
    }

    @Operation(summary = "删除知识库及其文档切片")
    @DeleteMapping("/bases/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    ApiResponse<?> deleteBase(@PathVariable long id, Authentication auth) {
        knowledge.deleteBase(id, uid(auth));
        return ApiResponse.ok();
    }

    @Operation(summary = "查询知识库文档及解析状态")
    @GetMapping("/bases/{id}/documents")
    @PreAuthorize("hasAuthority('ai:knowledge:list')")
    ApiResponse<?> documents(@PathVariable long id, Authentication auth) { return ApiResponse.ok(knowledge.documents(id, uid(auth))); }

    @Operation(summary = "上传文档并异步解析、切片、向量化")
    @PostMapping(value = "/bases/{id}/documents", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    ApiResponse<?> upload(@PathVariable long id, @RequestPart("file") MultipartFile file, Authentication auth) throws Exception {
        return ApiResponse.ok(java.util.Map.of("id", knowledge.upload(id, file, uid(auth)), "status", "PENDING"));
    }

    @Operation(summary = "重新处理失败或已完成的文档")
    @PostMapping("/documents/{id}/reprocess")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    ApiResponse<?> reprocess(@PathVariable long id, Authentication auth) {
        knowledge.reprocess(id, uid(auth));
        return ApiResponse.ok();
    }

    @Operation(summary = "查询文档父块和子块预览")
    @GetMapping("/documents/{id}/chunks")
    @PreAuthorize("hasAuthority('ai:knowledge:list')")
    ApiResponse<?> chunks(@PathVariable long id, @RequestParam(defaultValue = "") String type, Authentication auth) {
        return ApiResponse.ok(knowledge.chunks(id, uid(auth), type));
    }

    private long uid(Authentication auth) {
        return jdbc.sql("SELECT id FROM sys_user WHERE username=:username")
                .param("username", auth.getName()).query(Long.class).single();
    }
}
