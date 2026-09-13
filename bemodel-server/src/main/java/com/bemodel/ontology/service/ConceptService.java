package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import com.bemodel.ontology.mapper.TermMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ConceptService extends ServiceImpl<ConceptMapper, Concept> {

    private final AttributeMapper attributeMapper;
    private final RelationMapper relationMapper;
    private final TermMapper termMapper;
    private final MappingMapper mappingMapper;

    private static final Set<String> STATUS = Set.of("DRAFT", "REVIEW", "PUBLISHED", "DEPRECATED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("REVIEW"),
            "REVIEW", Set.of("PUBLISHED", "DRAFT"),
            "PUBLISHED", Set.of("DEPRECATED"),
            "DEPRECATED", Set.of("DRAFT")
    );

    public List<Concept> listByDomain(String domainCode) {
        return lambdaQuery()
                .eq(domainCode != null && !domainCode.isBlank(), Concept::getDomainCode, domainCode)
                .orderByAsc(Concept::getCode)
                .list();
    }

    public Concept getByCode(String code) {
        return lambdaQuery().eq(Concept::getCode, code).one();
    }

    public Map<String, Object> detail(String code) {
        Concept concept = getByCode(code);
        if (concept == null) {
            throw new BizException("概念不存在: " + code);
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("concept", concept);
        detail.put("attributes", attributeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Attribute>()
                        .eq(Attribute::getConceptCode, code).orderByAsc(Attribute::getSort)));
        detail.put("relations", relationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Relation>()
                        .eq(Relation::getFromConcept, code)
                        .or().eq(Relation::getToConcept, code)));
        detail.put("terms", termMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Term>()
                        .eq(Term::getConceptCode, code)));
        return detail;
    }

    public Concept create(Concept concept) {
        if (getByCode(concept.getCode()) != null) {
            throw new BizException("概念编码已存在: " + concept.getCode());
        }
        concept.setStatus("DRAFT");
        concept.setVersion(1);
        save(concept);
        return concept;
    }

    /** 仅草稿可删除；存在属性/关系/术语/字段映射任一引用时拒绝并列出数量 */
    public void deleteDraft(String code) {
        Concept concept = getByCode(code);
        if (concept == null) {
            throw new BizException("概念不存在: " + code);
        }
        if (!"DRAFT".equals(concept.getStatus())) {
            throw new BizException("仅草稿(DRAFT)状态可删除，当前状态: " + concept.getStatus());
        }
        long attrs = attributeMapper.selectCount(
                new LambdaQueryWrapper<Attribute>().eq(Attribute::getConceptCode, code));
        long rels = relationMapper.selectCount(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromConcept, code).or().eq(Relation::getToConcept, code));
        long terms = termMapper.selectCount(
                new LambdaQueryWrapper<Term>().eq(Term::getConceptCode, code));
        long mappings = mappingMapper.selectCount(
                new LambdaQueryWrapper<Mapping>().eq(Mapping::getConceptCode, code));
        if (attrs + rels + terms + mappings > 0) {
            throw new BizException(String.format(
                    "概念存在引用，拒绝删除: 属性%d个、关系%d条、术语%d条、字段映射%d条", attrs, rels, terms, mappings));
        }
        removeById(concept.getId());
    }

    public Concept transition(String code, String target) {
        Concept concept = getByCode(code);
        if (concept == null) {
            throw new BizException("概念不存在: " + code);
        }
        if (!STATUS.contains(target)) {
            throw new BizException("非法状态: " + target);
        }
        Set<String> allowed = TRANSITIONS.getOrDefault(concept.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new BizException("不允许从 " + concept.getStatus() + " 流转到 " + target);
        }
        concept.setStatus(target);
        if ("PUBLISHED".equals(target)) {
            concept.setVersion(concept.getVersion() + 1);
        }
        updateById(concept);
        return concept;
    }
}
