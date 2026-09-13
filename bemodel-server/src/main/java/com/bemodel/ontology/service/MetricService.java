package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.mapper.MetricMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MetricService extends ServiceImpl<MetricMapper, Metric> {

    private final DatasourceService datasourceService;

    /** 实测指标：到绑定数据源执行探针SQL，回写最近值并判定告警 */
    public Map<String, Object> evaluate(String metricCode) {
        Metric metric = getByCode(metricCode);
        if (metric == null) {
            throw new BizException("指标不存在: " + metricCode);
        }
        if (!StringUtils.hasText(metric.getProbeSql()) || !StringUtils.hasText(metric.getDsCode())) {
            throw new BizException("指标未绑定实测探针: " + metricCode);
        }
        Integer value = datasourceService.jdbc(metric.getDsCode())
                .queryForObject(metric.getProbeSql(), Integer.class);

        metric.setLastVal(value);
        metric.setLastEvalAt(LocalDateTime.now());
        updateById(metric);

        boolean alarm = metric.getWarnThreshold() != null
                && value != null && value > metric.getWarnThreshold();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricCode", metric.getMetricCode());
        result.put("name", metric.getName());
        result.put("value", value);
        result.put("warnThreshold", metric.getWarnThreshold());
        result.put("alarm", alarm);
        result.put("evaluatedAt", metric.getLastEvalAt());
        return result;
    }

    /** 全量实测（监控巡检） */
    public List<Map<String, Object>> evaluateAll() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Metric m : lambdaQuery().isNotNull(Metric::getProbeSql).list()) {
            try {
                results.add(evaluate(m.getMetricCode()));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("metricCode", m.getMetricCode());
                err.put("name", m.getName());
                err.put("alarm", true);
                err.put("error", e.getMessage());
                results.add(err);
            }
        }
        return results;
    }

    public Metric getByCode(String metricCode) {
        return lambdaQuery().eq(Metric::getMetricCode, metricCode).one();
    }
}
