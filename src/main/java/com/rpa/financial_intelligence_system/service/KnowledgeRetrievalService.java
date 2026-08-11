package com.rpa.financial_intelligence_system.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;

/** pgvector + PostgreSQL 全文检索 + RRF 融合检索服务。 */
@Service
public class KnowledgeRetrievalService {
    private static final int RRF_K = 60;
    private final JdbcClient jdbc;
    private final AiService ai;

    public KnowledgeRetrievalService(JdbcClient jdbc, AiService ai) {
        this.jdbc = jdbc;
        this.ai = ai;
    }

    public List<Map<String, Object>> search(List<Long> baseIds, long userId, String query, int topK) {
        if (baseIds == null || baseIds.isEmpty() || query == null || query.isBlank()) return List.of();
        List<Long> bases = baseIds.stream().filter(Objects::nonNull).distinct().toList();
        int limit = Math.min(Math.max(topK * 4, 8), 60);
        String vector = ai.embedding(query).toString();
        var vectorRows = jdbc.sql("""
                SELECT c.id,c.parent_chunk_id,c.content,c.page_number,
                       1-(c.embedding <=> cast(:vector AS vector)) similarity
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                JOIN knowledge_base kb ON kb.id=d.knowledge_base_id
                LEFT JOIN sys_user owner ON owner.id=kb.created_by JOIN sys_user viewer ON viewer.id=:userId
                WHERE c.chunk_type='CHILD' AND c.embedding IS NOT NULL AND d.status='COMPLETED'
                  AND kb.id IN (:baseIds)
                  AND (kb.permission_type='PUBLIC' OR kb.created_by=:userId OR
                       (kb.permission_type='DEPARTMENT' AND owner.department_id=viewer.department_id))
                ORDER BY c.embedding <=> cast(:vector AS vector) LIMIT :limit
                """).param("vector", vector).param("userId", userId).param("baseIds", bases).param("limit", limit).query().listOfRows();
        var keywordRows = jdbc.sql("""
                SELECT c.id,c.parent_chunk_id,c.content,c.page_number,
                       ts_rank_cd(c.search_vector, websearch_to_tsquery('simple', :query)) keyword_score
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                JOIN knowledge_base kb ON kb.id=d.knowledge_base_id
                LEFT JOIN sys_user owner ON owner.id=kb.created_by JOIN sys_user viewer ON viewer.id=:userId
                WHERE c.chunk_type='CHILD' AND d.status='COMPLETED' AND kb.id IN (:baseIds)
                  AND (c.search_vector @@ websearch_to_tsquery('simple', :query) OR c.content ILIKE '%'||:query||'%')
                  AND (kb.permission_type='PUBLIC' OR kb.created_by=:userId OR
                       (kb.permission_type='DEPARTMENT' AND owner.department_id=viewer.department_id))
                ORDER BY keyword_score DESC LIMIT :limit
                """).param("query", query.trim()).param("userId", userId).param("baseIds", bases).param("limit", limit).query().listOfRows();

        Map<Long, Fusion> fusion = new HashMap<>();
        for (int i = 0; i < vectorRows.size(); i++) {
            Map<String, Object> row = vectorRows.get(i);
            long parentId = ((Number) row.get("parent_chunk_id")).longValue();
            var item = fusion.computeIfAbsent(parentId, Fusion::new);
            item.score += 1d / (RRF_K + i + 1);
            item.vectorSimilarity = Math.max(item.vectorSimilarity, number(row.get("similarity")));
            item.childId = ((Number) row.get("id")).longValue();
        }
        for (int i = 0; i < keywordRows.size(); i++) {
            Map<String, Object> row = keywordRows.get(i);
            long parentId = ((Number) row.get("parent_chunk_id")).longValue();
            var item = fusion.computeIfAbsent(parentId, Fusion::new);
            item.score += 1d / (RRF_K + i + 1);
            item.keywordScore = Math.max(item.keywordScore, number(row.get("keyword_score")));
            item.childId = ((Number) row.get("id")).longValue();
        }
        if (fusion.isEmpty()) return List.of();

        var ranked = fusion.values().stream().sorted(Comparator.comparingDouble((Fusion x) -> x.score).reversed()).limit(topK).toList();
        List<Long> parentIds = ranked.stream().map(x -> x.parentId).toList();
        var parents = jdbc.sql("""
                SELECT p.id parent_id,p.content,p.page_number,p.metadata,
                       d.id document_id,d.original_name,d.file_id,
                       kb.id knowledge_base_id,kb.name knowledge_base_name
                FROM knowledge_chunk p JOIN knowledge_document d ON d.id=p.document_id
                JOIN knowledge_base kb ON kb.id=d.knowledge_base_id
                WHERE p.id IN (:parentIds)
                """).param("parentIds", parentIds).query().listOfRows();
        Map<Long, Map<String, Object>> parentMap = new HashMap<>();
        parents.forEach(row -> parentMap.put(((Number) row.get("parent_id")).longValue(), row));
        var result = new ArrayList<Map<String, Object>>();
        for (Fusion item : ranked) {
            Map<String, Object> parent = parentMap.get(item.parentId);
            if (parent == null) continue;
            var hit = new LinkedHashMap<String, Object>();
            hit.put("chunkId", item.parentId);
            hit.put("matchedChildId", item.childId);
            hit.put("content", parent.get("content"));
            hit.put("pageNumber", parent.get("page_number"));
            hit.put("documentId", parent.get("document_id"));
            hit.put("documentName", parent.get("original_name"));
            hit.put("fileId", parent.get("file_id"));
            hit.put("knowledgeBaseId", parent.get("knowledge_base_id"));
            hit.put("knowledgeBaseName", parent.get("knowledge_base_name"));
            hit.put("fusedScore", item.score);
            hit.put("vectorSimilarity", item.vectorSimilarity);
            hit.put("keywordScore", item.keywordScore);
            result.add(hit);
        }
        return result;
    }

    private double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0d; }

    private static final class Fusion {
        private final long parentId;
        private long childId;
        private double score;
        private double vectorSimilarity;
        private double keywordScore;

        private Fusion(long parentId) { this.parentId = parentId; }
    }
}
