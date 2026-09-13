package com.bemodel.modeling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Action;
import com.bemodel.modeling.entity.Release;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.mapper.ActionMapper;
import com.bemodel.modeling.mapper.ReleaseMapper;
import com.bemodel.modeling.mapper.RuleMapper;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.MetricMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import com.bemodel.ontology.mapper.TermMapper;
import com.bemodel.ontology.service.OntologyCheckService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本体版本发布：把当前全部「已发布」元素打成不可变快照（版本号自动递增）。
 * LLM 调用一律绑定最新发布版本，保证提示词可复现、效果可审计。
 * 发布前先跑本体自检（借鉴 Utopia：lint 先于 release）：
 * 存在 BLOCKER 一律拒绝；仅剩 WARN 时需 force=true 放行。
 */
@Service
@RequiredArgsConstructor
public class ReleaseService extends ServiceImpl<ReleaseMapper, Release> {

    private final ConceptMapper conceptMapper;
    private final AttributeMapper attributeMapper;
    private final RelationMapper relationMapper;
    private final TermMapper termMapper;
    private final MetricMapper metricMapper;
    private final RuleMapper ruleMapper;
    private final ActionMapper actionMapper;
    private final OntologyCheckService ontologyCheckService;
    private final ObjectMapper objectMapper;

    public Release publish(String changeSummary, String releasedBy) {
        return publish(changeSummary, releasedBy, false);
    }

    /**
     * 发布事务边界：自检 + 快照读取 + 单行写入整体原子（默认隔离级下快照视图一致，写入失败整体回滚）。
     * 方法内无 LLM 等慢调用（自检为纯本地规则演算），长事务风险可控。
     */
    @Transactional
    public Release publish(String changeSummary, String releasedBy, boolean force) {
        List<OntologyCheckService.Defect> defects = ontologyCheckService.check();
        List<OntologyCheckService.Defect> blockers = ontologyCheckService.blockers(defects);
        if (!blockers.isEmpty()) {
            throw new BizException("本体自检未通过，存在 " + blockers.size() + " 个阻断缺陷，禁止发布: "
                    + ontologyCheckService.describe(blockers));
        }
        if (!force && !defects.isEmpty()) {
            throw new BizException("本体自检存在 " + defects.size() + " 个警告，确认后可带 force=true 发布: "
                    + ontologyCheckService.describe(defects));
        }
        try {
            List<Concept> concepts = conceptMapper.selectList(
                    new LambdaQueryWrapper<Concept>().eq(Concept::getStatus, "PUBLISHED"));
            List<Attribute> attributes = attributeMapper.selectList(
                    new LambdaQueryWrapper<Attribute>().in(Attribute::getConceptCode,
                            concepts.stream().map(Concept::getCode).toList()));
            List<Relation> relations = relationMapper.selectList(null);
            List<Term> terms = termMapper.selectList(null);
            List<Metric> metrics = metricMapper.selectList(null);
            List<Rule> rules = ruleMapper.selectList(
                    new LambdaQueryWrapper<Rule>().eq(Rule::getStatus, "PUBLISHED"));
            List<Action> actions = actionMapper.selectList(
                    new LambdaQueryWrapper<Action>().eq(Action::getStatus, "PUBLISHED"));

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("concepts", concepts);
            snapshot.put("attributes", attributes);
            snapshot.put("relations", relations);
            snapshot.put("terms", terms);
            snapshot.put("metrics", metrics);
            snapshot.put("rules", rules);
            snapshot.put("actions", actions);

            Release release = new Release();
            release.setVersionTag(nextTag());
            release.setChangeSummary(changeSummary);
            release.setElementCount(concepts.size() + attributes.size() + relations.size()
                    + terms.size() + metrics.size() + rules.size() + actions.size());
            release.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
            release.setReleasedBy(releasedBy);
            save(release);
            return release;
        } catch (Exception e) {
            throw new RuntimeException("发布失败: " + e.getMessage(), e);
        }
    }

    /** 最新发布版本号；从未发布过返回 null */
    public String currentTag() {
        Release latest = getOne(new LambdaQueryWrapper<Release>()
                .orderByDesc(Release::getId).last("LIMIT 1"), false);
        return latest == null ? null : latest.getVersionTag();
    }

    private String nextTag() {
        String current = currentTag();
        if (current == null) {
            return "v1.0";
        }
        try {
            String[] parts = current.substring(1).split("\\.");
            return "v" + parts[0] + "." + (Integer.parseInt(parts[1]) + 1);
        } catch (Exception e) {
            return current + ".1";
        }
    }

    public List<Release> listAll() {
        return list(new LambdaQueryWrapper<Release>().orderByDesc(Release::getId)
                .select(Release.class, f -> !f.getColumn().equals("snapshot_json")));
    }
}
