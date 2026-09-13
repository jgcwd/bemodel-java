package com.bemodel.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.entity.PhysicalColumn;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.service.MissService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MappingService extends ServiceImpl<MappingMapper, Mapping> {

    private final SchemaScanService schemaScanService;
    private final ConceptMapper conceptMapper;
    private final AttributeMapper attributeMapper;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;
    private final MissService missService;

    public List<Mapping> list(String dsCode, String tableName) {
        return lambdaQuery()
                .eq(dsCode != null && !dsCode.isBlank(), Mapping::getDsCode, dsCode)
                .eq(tableName != null && !tableName.isBlank(), Mapping::getTableName, tableName)
                .list();
    }

    /** 批量保存映射（同一物理列重复保存时覆盖） */
    public void saveBatch(List<Mapping> mappings) {
        for (Mapping m : mappings) {
            Mapping existing = lambdaQuery()
                    .eq(Mapping::getDsCode, m.getDsCode())
                    .eq(Mapping::getTableName, m.getTableName())
                    .eq(Mapping::getColumnName, m.getColumnName())
                    .one();
            if (existing != null) {
                m.setId(existing.getId());
                updateById(m);
            } else {
                save(m);
            }
        }
    }

    /**
     * AI 智能映射推荐：物理表结构 + 已发布概念 → deepseek-v4-flash 返回建议映射。
     * LLM 不可用时降级为字段名/注释相似度规则。
     */
    public Map<String, Object> aiSuggest(String dsCode, String tableName) {
        List<PhysicalColumn> columns = schemaScanService.columns(dsCode, tableName);
        if (columns.isEmpty()) {
            throw new BizException("未找到物理表，请先扫描数据源: " + dsCode + "." + tableName);
        }
        List<Concept> concepts = conceptMapper.selectList(
                new LambdaQueryWrapper<Concept>().eq(Concept::getStatus, "PUBLISHED"));
        List<Attribute> attributes = attributeMapper.selectList(null);

        List<Map<String, Object>> suggestions;
        boolean llmUsed = false;

        String prompt = buildPrompt(tableName, columns, concepts, attributes);
        Optional<String> llmResp = deepSeekClient.chat("MAPPING_SUGGEST",
                "你是医疗信息化本体映射专家。只返回JSON数组，不要多余文字。", prompt);
        if (llmResp.isPresent()) {
            suggestions = parseLlmSuggestions(llmResp.get(), columns);
            llmUsed = !suggestions.isEmpty();
        } else {
            suggestions = List.of();
        }
        if (suggestions.isEmpty()) {
            suggestions = ruleSuggest(columns, concepts, attributes);
        }
        // 本体增长回路：某列没有任何概念候选可推荐时，按列注释（无则列名）采集 ATTRIBUTE miss
        for (PhysicalColumn col : columns) {
            boolean noCandidate = suggestions.stream()
                    .filter(s -> col.getColumnName().equals(s.get("column")))
                    .noneMatch(s -> s.get("conceptCode") != null && !s.get("conceptCode").toString().isBlank());
            if (noCandidate) {
                missService.recordMiss(
                        col.getColumnComment() == null || col.getColumnComment().isBlank()
                                ? col.getColumnName() : col.getColumnComment(),
                        "ATTRIBUTE", "MAPPING_AI");
            }
        }
        return Map.of(
                "llmUsed", llmUsed,
                "model", deepSeekClient.model(),
                "suggestions", suggestions);
    }

    private String buildPrompt(String tableName, List<PhysicalColumn> columns,
                               List<Concept> concepts, List<Attribute> attributes) {
        StringBuilder sb = new StringBuilder();
        sb.append("物理表 ").append(tableName).append(" 的字段：\n");
        for (PhysicalColumn c : columns) {
            sb.append("- ").append(c.getColumnName()).append(" (").append(c.getDataType())
                    .append(") 注释: ").append(c.getColumnComment() == null ? "" : c.getColumnComment()).append('\n');
        }
        sb.append("\n可选标准概念及属性：\n");
        for (Concept c : concepts) {
            sb.append("概念 ").append(c.getCode()).append("(").append(c.getName()).append("): ");
            List<String> attrs = attributes.stream()
                    .filter(a -> a.getConceptCode().equals(c.getCode()))
                    .map(a -> a.getAttrCode() + "(" + a.getAttrName() + ")")
                    .toList();
            sb.append(String.join(", ", attrs)).append('\n');
        }
        sb.append("\n请为每个物理字段推荐映射，返回JSON数组，元素格式：");
        sb.append("{\"column\":\"字段名\",\"conceptCode\":\"概念编码\",\"attrCode\":\"属性编码\",\"confidence\":0.0-1.0,\"reason\":\"理由\"}。");
        sb.append("无合适映射时 attrCode 填 null。只返回JSON。");
        return sb.toString();
    }

    private List<Map<String, Object>> parseLlmSuggestions(String resp, List<PhysicalColumn> columns) {
        try {
            String json = resp;
            int start = resp.indexOf('[');
            int end = resp.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = resp.substring(start, end + 1);
            }
            JsonNode arr = objectMapper.readTree(json);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode node : arr) {
                String column = node.path("column").asText();
                boolean exists = columns.stream().anyMatch(c -> c.getColumnName().equals(column));
                if (!exists) {
                    continue;
                }
                result.add(Map.of(
                        "column", column,
                        "conceptCode", node.path("conceptCode").asText(""),
                        "attrCode", node.path("attrCode").isNull() ? "" : node.path("attrCode").asText(""),
                        "confidence", node.path("confidence").asDouble(0.5),
                        "reason", node.path("reason").asText("")));
            }
            return result;
        } catch (Exception e) {
            log.warn("LLM 映射结果解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 降级规则：按列名/注释与属性编码/名称的包含关系打分 */
    private List<Map<String, Object>> ruleSuggest(List<PhysicalColumn> columns,
                                                  List<Concept> concepts,
                                                  List<Attribute> attributes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PhysicalColumn col : columns) {
            String colName = col.getColumnName().toLowerCase();
            String comment = col.getColumnComment() == null ? "" : col.getColumnComment();
            Map<String, Object> best = null;
            for (Attribute attr : attributes) {
                boolean hit = colName.contains(attr.getAttrCode().toLowerCase())
                        || (!comment.isEmpty() && comment.contains(attr.getAttrName()));
                if (hit) {
                    Concept concept = concepts.stream()
                            .filter(c -> c.getCode().equals(attr.getConceptCode())).findFirst().orElse(null);
                    if (concept != null) {
                        best = Map.of("column", col.getColumnName(),
                                "conceptCode", concept.getCode(),
                                "attrCode", attr.getAttrCode(),
                                "confidence", 0.6,
                                "reason", "规则匹配：列名/注释与属性相似");
                        break;
                    }
                }
            }
            if (best == null) {
                best = Map.of("column", col.getColumnName(), "conceptCode", "",
                        "attrCode", "", "confidence", 0.0, "reason", "未匹配，请人工指定");
            }
            result.add(best);
        }
        return result;
    }
}
