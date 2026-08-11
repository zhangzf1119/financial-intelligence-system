package com.rpa.financial_intelligence_system.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/** 知识库集合、文档 ETL 和父子切片管理。 */
@Service
public class KnowledgeDocumentService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final int PARENT_CHARS = 1800;
    private static final int CHILD_CHARS = 500;
    private static final int CHILD_OVERLAP = 100;

    private final JdbcClient jdbc;
    private final StorageService storage;
    private final KnowledgeParserService parser;
    private final AiService ai;
    private final KnowledgeEtlService etl;

    public KnowledgeDocumentService(JdbcClient jdbc, StorageService storage, KnowledgeParserService parser, AiService ai,
                                    KnowledgeEtlService etl) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.parser = parser;
        this.ai = ai;
        this.etl = etl;
    }

    public List<Map<String, Object>> bases(long userId) {
        return jdbc.sql("""
                SELECT kb.id,kb.name,kb.description,kb.permission_type,kb.created_by,kb.created_at,kb.updated_at,
                       u.nickname creator_name,
                       count(d.id) document_count,
                       count(d.id) FILTER (WHERE d.status='COMPLETED') completed_document_count
                FROM knowledge_base kb LEFT JOIN sys_user u ON u.id=kb.created_by
                LEFT JOIN knowledge_document d ON d.knowledge_base_id=kb.id
                WHERE kb.permission_type='PUBLIC' OR kb.created_by=:uid
                   OR (kb.permission_type='DEPARTMENT' AND EXISTS (
                     SELECT 1 FROM sys_user owner JOIN sys_user viewer ON viewer.id=:uid
                     WHERE owner.id=kb.created_by AND owner.department_id=viewer.department_id))
                GROUP BY kb.id,u.nickname ORDER BY kb.updated_at DESC,kb.id DESC
                """).param("uid", userId).query().listOfRows();
    }

    @Transactional
    public long createBase(String name, String description, String permissionType, long userId) {
        String permission = normalizePermission(permissionType);
        return jdbc.sql("INSERT INTO knowledge_base(name,description,permission_type,created_by) VALUES(:n,:d,:p,:u) RETURNING id")
                .param("n", name.trim()).param("d", description).param("p", permission).param("u", userId)
                .query(Long.class).single();
    }

    @Transactional
    public void updateBase(long id, String name, String description, String permissionType, long userId) {
        ensureBase(id, userId, true);
        jdbc.sql("UPDATE knowledge_base SET name=:n,description=:d,permission_type=:p,updated_at=now() WHERE id=:id")
                .param("n", name.trim()).param("d", description).param("p", normalizePermission(permissionType)).param("id", id).update();
    }

    @Transactional
    public void deleteBase(long id, long userId) {
        ensureBase(id, userId, true);
        jdbc.sql("DELETE FROM knowledge_base WHERE id=:id").param("id", id).update();
    }

    public List<Map<String, Object>> documents(long baseId, long userId) {
        ensureBase(baseId, userId, false);
        return jdbc.sql("""
                SELECT d.id,d.knowledge_base_id,d.file_id,d.original_name,d.status,d.parser_type,d.error_message,
                       d.chunk_total,d.parent_chunk_total,d.child_chunk_total,d.created_by,d.created_at,d.updated_at,
                       f.content_type,f.size_bytes,u.nickname creator_name
                FROM knowledge_document d JOIN sys_file f ON f.id=d.file_id LEFT JOIN sys_user u ON u.id=d.created_by
                WHERE d.knowledge_base_id=:baseId ORDER BY d.updated_at DESC,d.id DESC
                """).param("baseId", baseId).query().listOfRows();
    }

    public long upload(long baseId, MultipartFile file, long userId) throws Exception {
        ensureBase(baseId, userId, true);
        Map<String, Object> saved = storage.upload(file, userId);
        long fileId = ((Number) saved.get("id")).longValue();
        String originalName = String.valueOf(saved.get("name"));
        try {
            long documentId = jdbc.sql("""
                    INSERT INTO knowledge_document(knowledge_base_id,file_id,original_name,status,parser_type,created_by)
                    VALUES(:baseId,:fileId,:name,'PENDING',:parser,:uid) RETURNING id
                    """).param("baseId", baseId).param("fileId", fileId).param("name", originalName)
                    .param("parser", extension(originalName)).param("uid", userId).query(Long.class).single();
            etl.processAsync(documentId);
            return documentId;
        } catch (Exception e) {
            try { storage.delete(jdbc.sql("SELECT * FROM sys_file WHERE id=:id").param("id", fileId).query().singleRow()); } catch (Exception ignored) { }
            throw e;
        }
    }

    public void reprocess(long documentId, long userId) {
        Map<String, Object> document = document(documentId, userId, true);
        jdbc.sql("UPDATE knowledge_document SET status='PENDING',error_message=NULL,updated_at=now() WHERE id=:id")
                .param("id", documentId).update();
        etl.processAsync(documentId);
    }

    public List<Map<String, Object>> chunks(long documentId, long userId, String type) {
        Map<String, Object> document = document(documentId, userId, false);
        String filter = "";
        if ("PARENT".equalsIgnoreCase(type) || "CHILD".equalsIgnoreCase(type)) filter = " AND c.chunk_type=:type";
        var query = jdbc.sql("""
                SELECT c.id,c.document_id,c.parent_chunk_id,c.chunk_type,c.chunk_index,c.page_number,c.content,
                       c.token_count,c.embedding IS NOT NULL has_embedding,c.metadata,c.created_at
                FROM knowledge_chunk c WHERE c.document_id=:documentId
                """ + filter + " ORDER BY c.chunk_type DESC,c.chunk_index LIMIT 2000")
                .param("documentId", documentId);
        if (!filter.isBlank()) query.param("type", type.toUpperCase(Locale.ROOT));
        return query.query().listOfRows();
    }

    private Map<String, Object> document(long documentId, long userId, boolean writable) {
        var rows = jdbc.sql("""
                SELECT d.*,kb.created_by base_creator,kb.permission_type FROM knowledge_document d
                JOIN knowledge_base kb ON kb.id=d.knowledge_base_id WHERE d.id=:id
                """).param("id", documentId).query().listOfRows();
        if (rows.isEmpty()) throw new IllegalArgumentException("知识库文档不存在");
        Map<String, Object> row = rows.getFirst();
        ensureBase(((Number) row.get("knowledge_base_id")).longValue(), userId, writable);
        return row;
    }

    @Async("ragTaskExecutor")
    public void processAsync(long documentId) {
        try {
            jdbc.sql("UPDATE knowledge_document SET status='PROCESSING',error_message=NULL,updated_at=now() WHERE id=:id")
                    .param("id", documentId).update();
            Map<String, Object> document = jdbc.sql("SELECT * FROM knowledge_document WHERE id=:id")
                    .param("id", documentId).query().singleRow();
            Map<String, Object> file = jdbc.sql("SELECT * FROM sys_file WHERE id=:id AND status='ACTIVE'")
                    .param("id", document.get("file_id")).query().singleRow();
            List<KnowledgeParserService.ParsedPage> pages = parser.parse(file);
            if (pages.isEmpty()) throw new IllegalArgumentException("文档未提取到有效文本");

            jdbc.sql("DELETE FROM knowledge_chunk WHERE document_id=:documentId").param("documentId", documentId).update();
            int parentCount = 0, childCount = 0, globalIndex = 0;
            for (var page : pages) {
                for (String parentText : parentChunks(page.text())) {
                    if (parentText.isBlank()) continue;
                    long parentId = insertChunk(documentId, null, "PARENT", globalIndex++, page.pageNumber(), parentText, null);
                    parentCount++;
                    for (String childText : childChunks(parentText)) {
                        if (childText.isBlank()) continue;
                        List<Double> vector = ai.embedding(childText);
                        insertChunk(documentId, parentId, "CHILD", globalIndex++, page.pageNumber(), childText, vector);
                        childCount++;
                    }
                }
            }
            if (childCount == 0) throw new IllegalArgumentException("文档未生成有效切片");
            jdbc.sql("""
                    UPDATE knowledge_document SET status='COMPLETED',error_message=NULL,chunk_total=:total,
                    parent_chunk_total=:parents,child_chunk_total=:children,updated_at=now() WHERE id=:id
                    """).param("total", parentCount + childCount).param("parents", parentCount).param("children", childCount).param("id", documentId).update();
        } catch (Exception e) {
            String message = Optional.ofNullable(e.getMessage()).orElse("文档解析或向量化失败");
            log.error("知识库文档 ETL 失败，documentId={}: {}", documentId, message, e);
            try {
                jdbc.sql("UPDATE knowledge_document SET status='FAILED',error_message=:error,updated_at=now() WHERE id=:id")
                        .param("error", message.substring(0, Math.min(1000, message.length()))).param("id", documentId).update();
            } catch (Exception updateError) {
                log.error("知识库文档状态更新失败，documentId={}", documentId, updateError);
            }
        }
    }

    private long insertChunk(long documentId, Long parentId, String type, int index, Integer page, String content, List<Double> vector) {
        String embeddingSql = vector == null ? "NULL::vector" : "cast(:embedding AS vector)";
        String sql = "INSERT INTO knowledge_chunk(document_id,parent_chunk_id,chunk_type,chunk_index,page_number,content,token_count,embedding,metadata) "
                + "VALUES(:documentId,:parentId,:type,:idx,:page,:content,:tokens," + embeddingSql
                + ",jsonb_build_object('page_number',:page,'chunk_type',:type)) RETURNING id";
        var statement = jdbc.sql(sql).param("documentId", documentId).param("parentId", parentId).param("type", type)
                .param("idx", index).param("page", page).param("content", content).param("tokens", estimateTokens(content));
        if (vector != null) statement.param("embedding", vector.toString());
        return statement.query(Long.class).single();
    }

    private void ensureBase(long baseId, long userId, boolean writable) {
        var rows = jdbc.sql("""
                SELECT kb.id,kb.created_by,kb.permission_type,owner.department_id owner_department,viewer.department_id viewer_department
                FROM knowledge_base kb LEFT JOIN sys_user owner ON owner.id=kb.created_by JOIN sys_user viewer ON viewer.id=:uid
                WHERE kb.id=:id
                """).param("id", baseId).param("uid", userId).query().listOfRows();
        if (rows.isEmpty()) throw new IllegalArgumentException("知识库不存在");
        Map<String, Object> row = rows.getFirst();
        boolean owner = Objects.equals(((Number) row.get("created_by")).longValue(), userId);
        boolean department = "DEPARTMENT".equals(row.get("permission_type")) && Objects.equals(row.get("owner_department"), row.get("viewer_department"));
        boolean allowed = owner || "PUBLIC".equals(row.get("permission_type")) || department;
        if (!allowed || (writable && !owner)) throw new IllegalArgumentException("无权访问或修改该知识库");
    }

    private List<String> parentChunks(String text) {
        var result = new ArrayList<String>();
        String[] paragraphs = text.split("\\n{2,}");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) continue;
            if (current.length() > 0 && current.length() + paragraph.length() + 2 > PARENT_CHARS) {
                result.add(current.toString().trim()); current.setLength(0);
            }
            if (paragraph.length() <= PARENT_CHARS) current.append(paragraph).append("\n\n");
            else {
                if (current.length() > 0) { result.add(current.toString().trim()); current.setLength(0); }
                result.addAll(split(paragraph, PARENT_CHARS, 0));
            }
        }
        if (current.length() > 0) result.add(current.toString().trim());
        return result;
    }

    private List<String> childChunks(String parent) { return split(parent, CHILD_CHARS, CHILD_OVERLAP); }

    private List<String> split(String text, int size, int overlap) {
        var result = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            String item = text.substring(start, end).trim();
            if (!item.isBlank()) result.add(item);
            if (end == text.length()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return result;
    }

    private int estimateTokens(String text) { return Math.max(1, text.codePointCount(0, text.length()) / 2); }
    private String normalizePermission(String value) {
        String p = Optional.ofNullable(value).orElse("PRIVATE").toUpperCase(Locale.ROOT);
        if (!Set.of("PRIVATE", "DEPARTMENT", "PUBLIC").contains(p)) throw new IllegalArgumentException("知识库权限类型不合法");
        return p;
    }
    private String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
}
