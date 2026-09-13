package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.service.LinkService;
import com.bemodel.rca.entity.RcaCase;
import com.bemodel.rca.mapper.RcaCaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 智慧客服验证：打开工单自动完成诊断（无"发起"动作），
 * 一键处置生成退费申请 + 处置单 + 工单办结，且不可重复处置。
 * 用合成工单测试，结束全量清理，不污染演示工单。
 */
@SpringBootTest
class CsServiceTest {

    @Autowired
    private CsService csService;
    @Autowired
    private LinkService linkService;
    @Autowired
    private RcaCaseMapper caseMapper;
    @Autowired
    private DatasourceService datasourceService;

    private LinkNode ticket;

    @BeforeEach
    void setUp() {
        ticket = new LinkNode();
        ticket.setNodeType("TICKET");
        ticket.setRefNo("TEST-CS-001");
        ticket.setTitle("测试工单：住院患者检验项目撤销后仍收费");
        ticket.setConceptCode("FEE_DETAIL");
        ticket.setStatus("未处置");
        ticket.setOccurredAt(LocalDateTime.now());
        ticket.setPayload("{\"inhos_no\":\"ZY20260815001\",\"patient\":\"张建国\"}");
        linkService.save(ticket);
    }

    @AfterEach
    void tearDown() {
        // 清理合成数据：工单/处置单/诊断案例/测试退费申请
        linkService.remove(new LambdaQueryWrapper<LinkNode>().eq(LinkNode::getRefNo, "TEST-CS-001"));
        linkService.remove(new LambdaQueryWrapper<LinkNode>().eq(LinkNode::getRefNo, "DP-TEST-CS-001"));
        caseMapper.delete(new LambdaQueryWrapper<RcaCase>().eq(RcaCase::getTicketRef, "TEST-CS-001"));
        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        charge.update("DELETE FROM refund_apply WHERE reason LIKE '%TEST-CS-001%'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosisShouldRunAutomatically() {
        Map<String, Object> diag = csService.diagnosis(ticket.getId());

        assertNotNull(diag.get("case"), "应自动产出诊断案例");
        assertFalse(((List<?>) diag.get("steps")).isEmpty(), "应有AI排查路径（探针步骤）");
        assertNotNull(diag.get("report"), "应有诊断报告");
        assertNotNull(diag.get("customerReply"), "应有客户回复话术");
        assertTrue(String.valueOf(diag.get("customerReply")).length() > 20, "话术应有实质内容");
        assertNotNull(diag.get("suggestions"), "应有处置建议");

        // 幂等：再次打开复用同一诊断，不重复执行
        Map<String, Object> again = csService.diagnosis(ticket.getId());
        assertEquals(Boolean.FALSE, again.get("fresh"), "再次打开应复用既有诊断");
    }

    @Test
    @SuppressWarnings("unchecked")
    void askShouldRouteDispensePayQuestion() {
        Map<String, Object> r = csService.ask("没缴费可以发药吗？");

        assertEquals("缴费发药双向核对", r.get("intent"), "缴费发药问题应路由到双向核对");
        assertEquals("RULE", r.get("router"));
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) r.get("evidence");
        assertTrue(evidence.stream().anyMatch(e -> String.valueOf(e.get("label")).contains("先药后费")),
                "证据应含先药后费方向");
        assertTrue(evidence.stream().anyMatch(e -> String.valueOf(e.get("label")).contains("已缴费未发药")),
                "证据应含已缴费未发药方向");
        assertTrue(String.valueOf(r.get("answer")).contains("缴费是发药的前置环节"));
    }

    @Test
    void askShouldRouteDispenseSplitQuestion() {
        Map<String, Object> r = csService.ask("一个医嘱可以分开发药吗？");

        assertEquals("分次发药核对", r.get("intent"), "分开发药问题应路由到 1:N 核对而非缴费核对");
        assertTrue(String.valueOf(r.get("answer")).contains("1:N"));
    }

    @Test
    void askShouldRouteDispenseReturnQuestion() {
        Map<String, Object> r = csService.ask("发药后可以部分退药吗");

        assertEquals("退药核对", r.get("intent"), "退药问题应路由到退药×退费联动核对");
        assertTrue(String.valueOf(r.get("answer")).contains("退药"));
    }

    @Test
    void askShouldFallBackToMenuForUnknownQuestion() {
        Map<String, Object> r = csService.ask("今天天气怎么样");

        assertEquals("能力引导", r.get("intent"), "词表外问题应落到能力菜单而非编造答案");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refundActionShouldCloseTheLoop() {
        csService.diagnosis(ticket.getId()); // 先诊断（客服真实操作顺序）
        Map<String, Object> result = csService.refundAction(ticket.getId(), "客服 测试");

        assertTrue(((Number) result.get("refundCount")).intValue() >= 5, "应批量生成退费申请（≥5笔）");
        assertNotNull(result.get("disposalRef"), "应登记处置单");

        // 工单已办结，处置单进入链路视图
        assertEquals("已处置", linkService.getById(ticket.getId()).getStatus());
        assertNotNull(linkService.getByRefNo(String.valueOf(result.get("disposalRef"))));

        // 不可重复处置
        assertThrows(Exception.class, () -> csService.refundAction(ticket.getId(), "客服 测试"));
    }
}
