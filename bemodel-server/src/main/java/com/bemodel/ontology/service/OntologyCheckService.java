package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Disjoint;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.DisjointMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本体自检：发布前的结构公理一致性检查（借鉴 Utopia 的 lint 先于 release）。
 * 缺陷确定性输出：按 type + refs 排序，同一数据多次检查结果完全一致。
 */
@Service
@RequiredArgsConstructor
public class OntologyCheckService {

    private final RelationMapper relationMapper;
    private final ConceptMapper conceptMapper;
    private final DisjointMapper disjointMapper;

    public record Defect(String type, String severity, String message, List<String> refs) {
    }

    public List<Defect> check() {
        List<Defect> defects = new ArrayList<>();
        List<Relation> relations = relationMapper.selectList(null);
        List<Concept> concepts = conceptMapper.selectList(null);
        List<Disjoint> disjoints = disjointMapper.selectList(null);

        checkRelationAxioms(relations, defects);
        checkDisjoint(disjoints, concepts, defects);
        checkIriDuplicate(concepts, defects);

        defects.sort(Comparator.comparing(Defect::type)
                .thenComparing(d -> String.join(",", d.refs())));
        return defects;
    }

    public List<Defect> blockers(List<Defect> defects) {
        return defects.stream().filter(d -> "BLOCKER".equals(d.severity())).toList();
    }

    public List<Defect> warns(List<Defect> defects) {
        return defects.stream().filter(d -> "WARN".equals(d.severity())).toList();
    }

    /** 缺陷列表的报错文案（发布拦截时携带） */
    public String describe(List<Defect> defects) {
        return defects.stream()
                .map(d -> String.format("[%s/%s] %s %s", d.type(), d.severity(), d.message(), d.refs()))
                .collect(Collectors.joining("；"));
    }

    // ---------- 关系公理 ----------

    private void checkRelationAxioms(List<Relation> relations, List<Defect> defects) {
        Map<String, List<Relation>> byName = relations.stream()
                .collect(Collectors.groupingBy(Relation::getRelationName));
        for (Relation r : relations) {
            String ref = ref(r);
            if (isOne(r.getIsSymmetric()) && isOne(r.getIsAsymmetric())) {
                defects.add(new Defect("SYMMETRIC_ASYMMETRIC", "BLOCKER",
                        "关系同时声明了对称与反对称公理", List.of(ref)));
            }
            if (isOne(r.getIsTransitive()) && isOne(r.getIsFunctional())) {
                defects.add(new Defect("TRANSITIVE_FUNCTIONAL", "WARN",
                        "关系同时声明了传递与函数公理（传递闭包与唯一值语义冲突）", List.of(ref)));
            }
            String inverseOf = r.getInverseOf();
            if (inverseOf != null && !inverseOf.isBlank()) {
                if (inverseOf.equals(r.getRelationName())) {
                    defects.add(new Defect("INVERSE_SELF", "BLOCKER",
                            "关系的互逆关系指向自身", List.of(ref)));
                } else {
                    Relation inverse = resolveInverse(r, byName.getOrDefault(inverseOf, List.of()));
                    if (inverse == null) {
                        defects.add(new Defect("INVERSE_NOT_MUTUAL", "WARN",
                                "inverse_of 指向的关系不存在: " + inverseOf, List.of(ref)));
                    } else if (!r.getRelationName().equals(inverse.getInverseOf())) {
                        defects.add(new Defect("INVERSE_NOT_MUTUAL", "WARN",
                                "互逆未成对声明: " + inverseOf + " 的 inverse_of=" + inverse.getInverseOf(),
                                List.of(ref, ref(inverse))));
                    }
                }
            }
        }
    }

    /** 解析互逆关系：优先端点对调的同名关系，退化为任意同名关系 */
    private Relation resolveInverse(Relation r, List<Relation> candidates) {
        return candidates.stream()
                .filter(c -> r.getFromConcept().equals(c.getToConcept())
                        && r.getToConcept().equals(c.getFromConcept()))
                .findFirst()
                .orElse(candidates.stream().findFirst().orElse(null));
    }

    // ---------- 概念互斥 ----------

    private void checkDisjoint(List<Disjoint> disjoints, List<Concept> concepts, List<Defect> defects) {
        Set<String> conceptCodes = concepts.stream().map(Concept::getCode).collect(Collectors.toSet());
        for (Disjoint d : disjoints) {
            List<String> pair = List.of(d.getConceptACode(), d.getConceptBCode());
            if (d.getConceptACode().equals(d.getConceptBCode())) {
                defects.add(new Defect("DISJOINT_SELF", "BLOCKER",
                        "互斥行两端是同一概念", pair));
                continue;
            }
            List<String> missing = pair.stream().filter(c -> !conceptCodes.contains(c)).toList();
            if (!missing.isEmpty()) {
                defects.add(new Defect("DISJOINT_DANGLING", "WARN",
                        "互斥行引用了不存在的概念: " + String.join("、", missing), pair));
            }
        }
    }

    // ---------- IRI ----------

    private void checkIriDuplicate(List<Concept> concepts, List<Defect> defects) {
        Map<String, List<String>> byIri = new LinkedHashMap<>();
        for (Concept c : concepts) {
            if (c.getIri() != null && !c.getIri().isBlank()) {
                byIri.computeIfAbsent(c.getIri(), k -> new ArrayList<>()).add(c.getCode());
            }
        }
        for (Map.Entry<String, List<String>> e : byIri.entrySet()) {
            if (e.getValue().size() > 1) {
                List<String> codes = e.getValue().stream().sorted().toList();
                defects.add(new Defect("IRI_DUPLICATE", "BLOCKER",
                        "多个概念共用同一 IRI: " + e.getKey(), codes));
            }
        }
    }

    private boolean isOne(Integer v) {
        return v != null && v == 1;
    }

    /** 关系的稳定引用（bm_relation 无独立 code 列，用唯一键表示） */
    public static String ref(Relation r) {
        return r.getFromConcept() + "->" + r.getToConcept() + "#" + r.getRelationName();
    }
}
