package com.rpa.financial_intelligence_system.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 独立的异步 ETL 入口，避免同一 Service 内部调用导致 @Async 失效。 */
@Service
public class KnowledgeEtlService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEtlService.class);
    private static final int PARENT_CHARS = 1800;
    private static final int CHILD_CHARS = 500;
    private static final int CHILD_OVERLAP = 100;

    private final JdbcClient jdbc;
    private final KnowledgeParserService parser;
    private final AiService ai;

    public KnowledgeEtlService(JdbcClient jdbc, KnowledgeParserService parser, AiService ai) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.ai = ai;
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
                    """).param("total", parentCount + childCount).param("parents", parentCount)
                    .param("children", childCount).param("id", documentId).update();
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
}
