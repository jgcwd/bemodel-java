package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.service.LinkService;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.rca.entity.RcaCase;
import com.bemodel.rca.entity.RcaReport;
import com.bemodel.rca.mapper.RcaCaseMapper;
import com.bemodel.rca.mapper.RcaReportMapper;
import com.bemodel.rca.service.RcaEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 智慧客服：客服打开工单时平台自动完成诊断（复用 RCA 探针引擎），
 * 产出结论/证据/处置建议/客户话术；支持一键处置（生成退费申请 + 处置单），
 * 形成「客诉 → 自动诊断 → 处置 → 办结」的客服工作闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsService {

    private final LinkService linkService;
    private final RcaEngine rcaEngine;
    private final RcaCaseMapper caseMapper;
    private final RcaReportMapper reportMapper;
    private final DatasourceService datasourceService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;
    private final com.bemodel.flow.FlowService flowService;
    private final com.bemodel.search.SearchService searchService;
    private final SemanticQaService semanticQaService;

    /**
     * 工单智能诊断：已有完成的诊断直接复用，否则自动执行（客服无感，打开即得结论）。
     * 返回：诊断过程 + 报告 + 处置建议 + 给客户的回复话术。
     */
    public Map<String, Object> diagnosis(Long ticketId) {
        LinkNode ticket = ticket(ticketId);
        RcaCase rcaCase = caseMapper.selectOne(new LambdaQueryWrapper<RcaCase>()
                .eq(RcaCase::getTicketRef, ticket.getRefNo()).orderByDesc(RcaCase::getId).last("LIMIT 1"));
        boolean fresh = false;
        if (rcaCase == null || !"DONE".equals(rcaCase.getStatus()) || legacyStepNames(rcaCase.getId())) {
            // 旧版案例的步骤名是「探针Pn」技术措辞，自动重跑为业务语言版本
            rcaCase = rcaEngine.start(ticket.getRefNo());
            fresh = true;
        }

        Map<String, Object> detail = rcaEngine.caseDetail(rcaCase.getId());
        RcaReport report = (RcaReport) detail.get("report");

        Map<String, Object> result = new LinkedHashMap<>(detail);
        result.put("ticket", ticket);
        result.put("fresh", fresh);
        result.put("suggestions", parseJson(report == null ? null : report.getSuggestionsJson()));
        result.put("impact", parseJson(report == null ? null : report.getImpactJson()));
        result.put("customerReply", customerReply(ticket, rcaCase, report));
        return result;
    }

    /**
     * 一键处置：按诊断结论生成退费申请（demo_charge.refund_apply，申请中），
     * 登记处置单（link_node.DISPOSAL），工单办结。
     */
    @Transactional
    public Map<String, Object> refundAction(Long ticketId, String operator) {
        LinkNode ticket = ticket(ticketId);
        if ("已处置".equals(ticket.getStatus())) {
            throw new BizException("工单已处置，无需重复操作");
        }

        // 影响面费用（与 RCA 探针 P4 同一口径：医嘱已取消但费用未退）
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<Map<String, Object>> affected = his.queryForList(
                "SELECT f.fee_id, f.inhos_no, f.item_name, f.amount FROM fee_detail f " +
                        "JOIN medical_order o ON f.order_id = o.order_id " +
                        "WHERE o.order_status = '2' AND f.fee_status = '1'");

        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        BigDecimal total = BigDecimal.ZERO;
        List<String> refundIds = new ArrayList<>();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        for (Map<String, Object> fee : affected) {
            String refundId = "RA" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + String.format("%04d", refundIds.size() + 1);
            charge.update("INSERT INTO refund_apply(refund_id,inhos_no,fee_id,amount,reason,apply_time,status) VALUES(?,?,?,?,?,?,?)",
                    refundId, fee.get("inhos_no"), fee.get("fee_id"), fee.get("amount"),
                    "客诉「撤销后仍收费」批量退费（工单 " + ticket.getRefNo() + "，经办 " + operator + "）",
                    now, "0");
            refundIds.add(refundId);
            total = total.add((BigDecimal) fee.get("amount"));
        }

        // 登记处置单（链路节点：处置进入概念的全生命周期视图）
        LinkNode disposal = new LinkNode();
        disposal.setNodeType("DISPOSAL");
        disposal.setRefNo("DP-" + ticket.getRefNo());
        disposal.setTitle("批量退费处置：" + refundIds.size() + " 笔，合计 ¥" + total);
        disposal.setConceptCode(ticket.getConceptCode());
        disposal.setStatus("已完成");
        disposal.setOccurredAt(LocalDateTime.now());
        disposal.setPayload("{\"ticketRef\":\"" + ticket.getRefNo() + "\",\"refundIds\":"
                + refundIds.size() + ",\"totalAmount\":" + total + ",\"operator\":\"" + operator + "\"}");
        linkService.save(disposal);

        // 工单办结
        ticket.setStatus("已处置");
        linkService.updateById(ticket);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundCount", refundIds.size());
        result.put("totalAmount", total);
        result.put("refundIds", refundIds);
        result.put("disposalRef", disposal.getRefNo());
        return result;
    }

    /** 给客户看的回复话术：LLM 生成（无 Key 降级为模板拼装，演示不断链） */
    private String customerReply(LinkNode ticket, RcaCase rcaCase, RcaReport report) {
        String rootCause = report == null ? rcaCase.getConclusion() : report.getRootCause();
        if (deepSeekClient.enabled()) {
            String user = "你是医院客服主管。根据以下客诉工单与平台诊断结论，写一段给客户的中文回复"
                    + "（150字内，先致歉，再说清原因与整改措施，口语化，不要技术术语）。\n"
                    + "工单：" + ticket.getTitle() + "\n诊断结论：" + rootCause;
            Optional<String> reply = deepSeekClient.chat("CS_REPLY",
                    "你是医院客服主管，回复要专业、诚恳、简短。", user);
            if (reply.isPresent()) {
                return reply.get();
            }
        }
        return "您好，非常抱歉给您带来了困扰。您反馈的「" + ticket.getTitle() + "」我们已核实："
                + "是系统升级后状态字典未同步导致的计费异常，涉及的费用将原路退回。"
                + "我们已同步完成规则修复并补充了核查机制，避免此类问题再次发生。感谢您的监督与理解。";
    }

    private boolean legacyStepNames(Long caseId) {
        RcaReport report = reportMapper.selectOne(
                new LambdaQueryWrapper<RcaReport>().eq(RcaReport::getCaseId, caseId));
        if (report != null && report.getEvidenceJson() != null && report.getEvidenceJson().contains("\"P1 ")) {
            return true;
        }
        return rcaEngine.caseDetail(caseId).get("steps") instanceof List<?> steps
                && steps.stream().anyMatch(s -> String.valueOf(
                        ((com.bemodel.rca.entity.RcaStep) s).getStepName()).startsWith("探针P"));
    }

    private LinkNode ticket(Long ticketId) {
        LinkNode ticket = linkService.getById(ticketId);
        if (ticket == null || !"TICKET".equals(ticket.getNodeType())) {
            throw new BizException("客服工单不存在: " + ticketId);
        }
        return ticket;
    }

    // ==================== 问一问：免培训的自然语言入口 ====================

    /**
     * 客服问答：自然语言问题 → 意图路由 → 调用平台真实能力（跨库查询/主数据/口径库/语义层Ontology2SQL）→
     * 结构化回答（结论 + 证据 + 可跳转的处置入口）。规则路由保证演示确定性；
     * LLM 负责意图归类与答案组织，数字一律来自真实查询，不可用也能完整作答。
     */
    public Map<String, Object> ask(String question) {
        String q = question == null ? "" : question.trim();
        // LLM 语义分类为主路由（有 key 时）；无 key 降级为关键词匹配。
        // 无论哪条路，作答都走同一批真实查询处理器——LLM 只做意图归类，不生成答案内容。
        String intent = null;
        String router = "NONE";
        if (deepSeekClient.enabled()) {
            intent = routeByLlm(q);
            if (intent != null) {
                router = "LLM";
            }
        }
        if (intent == null) {
            intent = routeByKeyword(q);
            if (intent != null) {
                router = "RULE";
            }
        }
        // 语义路径单列：答不了时问题已回流增长回路，菜单需诚实提示
        if ("SEMANTIC_QUERY".equals(intent)) {
            SemanticQaService.Outcome outcome = semanticQaService.answer(q);
            if (outcome.result() != null) {
                outcome.result().putIfAbsent("router", router); // 语义查询处理器自带 router=SEMANTIC
                return outcome.result();
            }
            Map<String, Object> menu = capabilityMenu(q);
            menu.put("router", router);
            if (outcome.recordedMiss()) {
                menu.put("answer", menu.get("answer")
                        + "这个问题已记录为本体完善提案（本体页-扩展提案可见）。");
            }
            return menu;
        }
        Map<String, Object> r = intent == null ? null : dispatch(intent, q);
        if (r != null) {
            r.putIfAbsent("router", router);
            return r;
        }
        Map<String, Object> menu = capabilityMenu(q);
        menu.put("router", intent != null ? router : "NONE");
        return menu;
    }

    private String routeByKeyword(String q) {
        if (matches(q, "取消", "撤销", "未退", "多收", "退费", "收费")) {
            return "FEE";
        }
        if (matches(q, "口径", "怎么算", "什么是", "什么叫", "定义")) {
            return "GLOSSARY";
        }
        if (matches(q, "分开发药", "分次发药", "多次发药", "拆零", "分批")) {
            return "DISPENSE_SPLIT";
        }
        if (matches(q, "退药", "退掉", "退货")) {
            return "DISPENSE_RETURN";
        }
        if (matches(q, "发药", "拿药", "取药", "买药", "药费", "缴费")) {
            return "DISPENSE_PAY";
        }
        if (matches(q, "耗材", "物资", "申领", "库存")) {
            return "MATERIAL";
        }
        if (matches(q, "医生", "护士", "药师", "技师", "谁", "科室", "人员")) {
            return "STAFF";
        }
        return null;
    }

    private String routeByLlm(String q) {
        Optional<String> r = deepSeekClient.chat("CS_ROUTE",
                "你是医院信息平台的意图分类器，只输出标签本身，不要任何解释。",
                "把下面的用户问题分到最合适的一类，只回答标签（一个词）：\n"
                        + "FEE=费用投诉工单（取消未退费/多收费的投诉排查与处置）；\n"
                        + "DISPENSE_PAY=缴费与发药的双向核对（没缴费能否发药、已缴费未发药滞留）；\n"
                        + "DISPENSE_SPLIT=一个医嘱能否拆成多次/分开发药的规则咨询；\n"
                        + "DISPENSE_RETURN=发药后的退药规则咨询（部分退药、退药退费联动）；\n"
                        + "MATERIAL=耗材申领流程咨询；\n"
                        + "STAFF=人员归属（某人是谁/哪个科室/什么职称）；\n"
                        + "GLOSSARY=指标口径与名词定义（怎么算/什么是/叫什么）；\n"
                        + "SEMANTIC_QUERY=对业务事实的开放查询（数量/明细/统计/状态/库存/金额/名单），"
                        + "以及缴费发药域之外的「能不能/可不可以/是否允许」类业务规则问题"
                        + "（如合并结算、跨科室发药等），平台可按本体映射直接查业务库或按本体结构推理回答；\n"
                        + "OTHER=以上都不是。\n"
                        + "注意：FEE/DISPENSE_PAY/DISPENSE_SPLIT/DISPENSE_RETURN/MATERIAL/STAFF/GLOSSARY 是专属能力，"
                        + "只在问题问规则、流程、投诉、口径定义时选；只要问题是「查一个业务事实」"
                        + "（多少数量、哪些记录、库存还有多少、金额合计、某个状态），一律选 SEMANTIC_QUERY。\n"
                        + "辨析：DISPENSE_PAY 处理「缴费与发药两个方向的对账」（含已缴费未发药滞留）；"
                        + "涉及缴费/发药/退药的「正常吗/可以吗」也归对应 DISPENSE_* 类，不进 SEMANTIC_QUERY；"
                        + "结算方式/合并结算等缴费发药域外的规则问题才选 SEMANTIC_QUERY。\n"
                        + "示例：「已缴费未发药正常吗」→ DISPENSE_PAY；「多个患者的处方可以一起结算吗」→ SEMANTIC_QUERY。\n"
                        + "问题：" + q);
        if (r.isEmpty()) {
            return null;
        }
        String label = r.get().trim().toUpperCase().replaceAll("[^A-Z_]", "");
        return switch (label) {
            case "FEE", "DISPENSE_PAY", "DISPENSE_SPLIT", "DISPENSE_RETURN", "MATERIAL", "STAFF", "GLOSSARY",
                 "SEMANTIC_QUERY" -> label;
            default -> null;
        };
    }

    private Map<String, Object> dispatch(String intent, String q) {
        return switch (intent) {
            case "FEE" -> feeAnswer(q);
            case "DISPENSE_PAY" -> dispensePayAnswer(q);
            case "DISPENSE_SPLIT" -> dispenseSplitAnswer(q);
            case "DISPENSE_RETURN" -> dispenseReturnAnswer(q);
            case "MATERIAL" -> materialAnswer(q);
            case "STAFF" -> staffAnswer(q);
            case "GLOSSARY" -> glossaryAnswer(q);
            default -> null;
        };
    }

    private Map<String, Object> baseResult(String q) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);
        return result;
    }

    /** 意图：取消未退费 / 多收费 / 客诉排查 */
    private Map<String, Object> feeAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        Map<String, Object> impact = his.queryForMap(
                "SELECT COUNT(DISTINCT f.inhos_no) AS patient_cnt, COUNT(*) AS fee_cnt, " +
                        "IFNULL(SUM(f.amount),0) AS total_amount FROM fee_detail f " +
                        "JOIN medical_order o ON f.order_id = o.order_id " +
                        "WHERE o.order_status = '2' AND f.fee_status = '1'");
        List<LinkNode> tickets = linkService.lambdaQuery()
                .eq(LinkNode::getNodeType, "TICKET").eq(LinkNode::getStatus, "待处理").list();
        result.put("intent", "取消未退费排查");
        result.put("answer", String.format(
                "当前全院共有 %s 名患者、%s 笔「医嘱已取消但费用未退」，合计 ¥%s。根因是 LIS v5.2 升级后撤销码 X→C，"
                        + "HIS 计费适配器未同步。现有 %s 张待处理工单，点开即可看 AI 的完整排查过程并一键退费。",
                impact.get("patient_cnt"), impact.get("fee_cnt"), impact.get("total_amount"), tickets.size()));
        result.put("evidence", List.of(
                Map.of("label", "影响患者", "value", impact.get("patient_cnt") + " 人"),
                Map.of("label", "未退费用", "value", impact.get("fee_cnt") + " 笔"),
                Map.of("label", "涉及金额", "value", "¥" + impact.get("total_amount")),
                Map.of("label", "待处理工单", "value", tickets.size() + " 张")));
        result.put("links", tickets.isEmpty()
                ? List.of(Map.of("label", "去链路追溯看费用明细概念", "route", "/link?concept=FEE_DETAIL"))
                : List.of(Map.of("label", "打开待处理工单", "route", "/cs?ticketId=" + tickets.get(0).getId()),
                        Map.of("label", "去链路追溯看费用明细概念", "route", "/link?concept=FEE_DETAIL")));
        return result;
    }

    /** 意图：缴费与发药双向核对（先药后费违规 / 已缴费未发药滞留）—— 三库实时核对 */
    private Map<String, Object> dispensePayAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        Set<String> paid = new HashSet<>(charge.queryForList(
                "SELECT DISTINCT inhos_no FROM pay_record WHERE pay_type IN ('1','2','4')", String.class));

        // 方向A：已发药但患者无任何缴费记录（先药后费，违规）
        List<Map<String, Object>> dispensed = pharmacy.queryForList(
                "SELECT dispense_id, patient_no, item_name FROM dispense_record WHERE status = '1'");
        List<Map<String, Object>> unpaid = dispensed.stream()
                .filter(d -> !paid.contains(String.valueOf(d.get("patient_no"))))
                .toList();

        // 方向B：药品医嘱已计费且患者有缴费，但药房无发药记录或仅有已退药（滞留积压）——跨库内存 join
        List<Map<String, Object>> chargedDrugOrders = his.queryForList(
                "SELECT DISTINCT o.order_id, o.inhos_no, o.item_name FROM medical_order o "
                        + "JOIN fee_detail f ON f.order_id = o.order_id "
                        + "WHERE o.order_type = '药品' AND o.order_status IN ('0','1')");
        Set<String> delivered = new HashSet<>(pharmacy.queryForList(
                "SELECT DISTINCT order_id FROM dispense_record WHERE status IN ('0','1')", String.class));
        List<Map<String, Object>> backlog = chargedDrugOrders.stream()
                .filter(o -> paid.contains(String.valueOf(o.get("inhos_no")))
                        && !delivered.contains(String.valueOf(o.get("order_id"))))
                .toList();

        result.put("intent", "缴费发药双向核对");
        result.put("answer", String.format(
                "流程规则：缴费是发药的前置环节，先药后费属违规；反方向「已缴费未发药」是发药滞留，同样需要核查。"
                        + "跨三库实测：已发药 %d 笔中先药后费 %d 笔；已计费药品医嘱 %d 条中已缴费未发药 %d 条%s。",
                dispensed.size(), unpaid.size(), chargedDrugOrders.size(), backlog.size(),
                unpaid.isEmpty() && backlog.isEmpty() ? "，两个方向都无异常" : "，异常需逐笔核查"));
        result.put("evidence", List.of(
                Map.of("label", "先药后费（违规）", "value", unpaid.size() + " 笔 / 已发药共 " + dispensed.size() + " 笔"),
                Map.of("label", "已缴费未发药（滞留）", "value", backlog.size() + " 条 / 已计费药品医嘱共 "
                        + chargedDrugOrders.size() + " 条"),
                Map.of("label", "数据来源", "value",
                        "DS_PHARMACY.dispense_record × DS_CHARGE.pay_record × DS_HIS.medical_order×fee_detail 内存核对")));
        result.put("links", List.of(
                Map.of("label", "去流程演示页看住院闭环", "route", "/flow"),
                Map.of("label", "去本体页看发药记录概念", "route", "/ontology?concept=DISPENSE")));
        return result;
    }

    /** 意图：一个医嘱能否拆成多次/分开发药 —— 医嘱×发药 1:N 关系的实时核对 */
    private Map<String, Object> dispenseSplitAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        List<Map<String, Object>> split = pharmacy.queryForList(
                "SELECT order_id, COUNT(*) AS cnt FROM dispense_record WHERE status IN ('0','1') "
                        + "GROUP BY order_id HAVING COUNT(*) > 1");
        Long totalOrders = pharmacy.queryForObject(
                "SELECT COUNT(DISTINCT order_id) FROM dispense_record WHERE status IN ('0','1')", Long.class);
        Long totalDispensed = pharmacy.queryForObject(
                "SELECT COUNT(*) FROM dispense_record WHERE status = '1'", Long.class);
        result.put("intent", "分次发药核对");
        result.put("answer", String.format(
                "可以。本体上「医嘱—调剂发药→发药记录」是 1:N 关系，一张药品医嘱允许拆成多次调剂/发药（拆零、分批发药都是合法场景）。"
                        + "实时核对药房库：当前 %d 条医嘱共产生 %d 笔发药，其中 %d 条医嘱存在多次发药%s。",
                totalOrders, totalDispensed, split.size(),
                split.isEmpty() ? "——目前全部是一单一发，未发生分次" : ""));
        result.put("evidence", List.of(
                Map.of("label", "有发药的医嘱", "value", totalOrders + " 条"),
                Map.of("label", "发药总笔数", "value", totalDispensed + " 笔"),
                Map.of("label", "多次发药医嘱", "value", split.size() + " 条"),
                Map.of("label", "数据来源", "value", "DS_PHARMACY.dispense_record 按医嘱分组实时统计")));
        result.put("links", List.of(
                Map.of("label", "去本体页看「医嘱—发药」关系", "route", "/ontology?concept=DISPENSE"),
                Map.of("label", "去流程演示页看发药环节", "route", "/flow")));
        return result;
    }

    /** 意图：发药后能否（部分）退药 —— 退药×退费联动的实时核对 */
    private Map<String, Object> dispenseReturnAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<Map<String, Object>> returned = pharmacy.queryForList(
                "SELECT dispense_id, order_id, item_name FROM dispense_record WHERE status = '2'");
        Long refundedFee = 0L;
        if (!returned.isEmpty()) {
            String in = returned.stream()
                    .map(r -> "'" + String.valueOf(r.get("order_id")).replace("'", "") + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            refundedFee = his.queryForObject(
                    "SELECT COUNT(*) FROM fee_detail WHERE order_id IN (" + in + ") AND fee_status = '2'", Long.class);
        }
        result.put("intent", "退药核对");
        result.put("answer", String.format(
                "可以退药（含部分退药），闭环上退药是发药的逆环节：药房把发药记录置为已退药，收费侧同步退费，两步必须成对。"
                        + "实时核对：当前已退药 %d 笔，对应费用已退费 %d 笔%s。",
                returned.size(), refundedFee,
                returned.size() == refundedFee ? "，退药退费全部联动一致" : "，存在退药未退费的裂缝，需核查"));
        result.put("evidence", List.of(
                Map.of("label", "已退药笔数", "value", returned.size() + " 笔"),
                Map.of("label", "费用已退费", "value", refundedFee + " 笔"),
                Map.of("label", "数据来源", "value", "DS_PHARMACY.dispense_record × DS_HIS.fee_detail 按医嘱号核对")));
        result.put("links", List.of(
                Map.of("label", "去本体页看发药记录概念", "route", "/ontology?concept=DISPENSE"),
                Map.of("label", "去流程演示页看闭环", "route", "/flow")));
        return result;
    }

    /** 意图：耗材库存 / 申领 */
    private Map<String, Object> materialAnswer(String q) {
        Map<String, Object> result = baseResult(q);
        JdbcTemplate material = datasourceService.jdbc("DS_MATERIAL");
        List<Map<String, Object>> stocks = material.queryForList(
                "SELECT material_name, quantity, unit FROM material_stock ORDER BY quantity");
        Long pending = material.queryForObject(
                "SELECT COUNT(*) FROM material_apply WHERE status = '待发'", Long.class);
        StringBuilder sb = new StringBuilder("耗材库存当前（库存=Σ入库-Σ出库，账实相符）：");
        for (Map<String, Object> s : stocks) {
            sb.append(s.get("material_name")).append(" ").append(s.get("quantity"))
                    .append(s.get("unit")).append("；");
        }
        sb.append("另有 ").append(pending).append(" 张科室申领单待发放。");
        result.put("intent", "耗材库存查询");
        result.put("answer", sb.toString());
        result.put("evidence", stocks.stream().map(s -> Map.of(
                "label", String.valueOf(s.get("material_name")),
                "value", s.get("quantity") + " " + s.get("unit"))).toList());
        result.put("links", List.of(Map.of("label", "去实例浏览看耗材库存/申领/发放", "route", "/ontology")));
        return result;
    }

    /** 意图：找人 / 人员归属（问的人不在主数据中时返回 null，继续走后续路由） */
    private Map<String, Object> staffAnswer(String q) {
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<Map<String, Object>> staffs = his.queryForList(
                "SELECT staff_name, role, title, dept_code FROM staff");
        Map<String, Object> hit = staffs.stream()
                .filter(s -> q.contains(String.valueOf(s.get("staff_name"))))
                .findFirst().orElse(null);
        if (hit == null) {
            return null;
        }
        Map<String, Object> result = baseResult(q);
        Map<String, Object> detail = flowService.staffDetail(String.valueOf(hit.get("staff_name")));
        @SuppressWarnings("unchecked")
        Map<String, Object> dept = (Map<String, Object>) detail.get("dept");
        @SuppressWarnings("unchecked")
        Map<String, Object> footprint = (Map<String, Object>) detail.get("footprint");
        result.put("intent", "人员归属查询");
        result.put("answer", hit.get("staff_name") + "：" + hit.get("role") + " · " + hit.get("title")
                + "，隶属" + dept.get("dept_name") + "（" + dept.get("category") + "）。业务足迹：开立医嘱 "
                + footprint.get("开立医嘱") + " 条、执行确认 " + footprint.get("执行确认") + " 次、处方审核 "
                + footprint.get("处方审核") + " 次、调剂发药 " + footprint.get("调剂发药") + " 次。");
        result.put("evidence", List.of(
                Map.of("label", "科室", "value", dept.get("dept_name") + "（" + dept.get("category") + "）"),
                Map.of("label", "职称", "value", String.valueOf(hit.get("title"))),
                Map.of("label", "数据来源", "value", "人员主数据 DS_HIS.staff（流程页人名可点击下钻）")));
        result.put("links", List.of(Map.of("label", "去流程演示页看人名下钻", "route", "/flow")));
        return result;
    }

    /** 意图：口径/定义/怎么算（无命中时返回 null，继续走后续路由） */
    private Map<String, Object> glossaryAnswer(String q) {
        Map<String, Object> search = searchService.search(q.replaceAll("(怎么算|什么是|什么叫|的口径|口径|定义)", ""));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) search.get("hits");
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        Map<String, Object> result = baseResult(q);
        Map<String, Object> top = hits.get(0);
        result.put("intent", "口径查询");
        result.put("answer", "「" + top.get("title") + "」" + top.get("content")
                + (Boolean.TRUE.equals(search.get("llmUsed")) ? "\n\n" + search.get("answer") : ""));
        result.put("evidence", List.of(Map.of("label", "命中" + top.get("type"), "value", String.valueOf(top.get("title")))));
        result.put("links", List.of(Map.of("label", "去统一口径页查看全部术语", "route", "/glossary")));
        return result;
    }

    /** 兜底：能力菜单 */
    private Map<String, Object> capabilityMenu(String q) {
        Map<String, Object> result = baseResult(q);
        result.put("intent", "能力引导");
        result.put("answer", "我目前能查证这几类问题，也可以直接问我业务数据（如「内科有多少住院患者」）：");
        result.put("evidence", List.of(
                Map.of("label", "开放查询", "value", "「头孢克肟还有多少库存？」「昨天的缴费总额是多少？」→ 本体语义层直接查业务库"),
                Map.of("label", "费用投诉", "value", "「检验取消了怎么还收费？」→ 全院取消未退费排查 + 一键退费工单"),
                Map.of("label", "缴费发药", "value", "「没缴费可以发药吗？」→ 发药×缴费跨库实时核对"),
                Map.of("label", "发药方式", "value", "「一个医嘱可以分开发药吗？」→ 医嘱×发药 1:N 实时统计"),
                Map.of("label", "退药核对", "value", "「发药后可以部分退药吗？」→ 退药×退费联动核对"),
                Map.of("label", "耗材库存", "value", "「一次性输液器还有多少库存？」→ 库存=Σ入-Σ出实时账"),
                Map.of("label", "人员归属", "value", "「王芳是谁？」→ 科室/职称/业务足迹"),
                Map.of("label", "指标口径", "value", "「出院人数怎么算？」→ 标准定义与负责人")));
        result.put("links", List.of());
        return result;
    }

    private boolean matches(String q, String... keywords) {
        for (String k : keywords) {
            if (q.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parseJson(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
