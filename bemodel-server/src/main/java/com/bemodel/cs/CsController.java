package com.bemodel.cs;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cs")
@RequiredArgsConstructor
public class CsController {

    private final CsService csService;

    /** 工单智能诊断（打开即自动完成，返回排查路径/结论/证据/建议/客户话术） */
    @GetMapping("/ticket/{id}/diagnosis")
    public Result<Map<String, Object>> diagnosis(@PathVariable Long id) {
        return Result.ok(csService.diagnosis(id));
    }

    /** 一键处置：生成退费申请 + 处置单 + 工单办结 */
    @PostMapping("/ticket/{id}/refund")
    public Result<Map<String, Object>> refund(@PathVariable Long id,
                                              @RequestParam(defaultValue = "客服 小周") String operator) {
        return Result.ok(csService.refundAction(id, operator));
    }

    /** 问一问：自然语言提问（scene=CS 客服 / ANALYTICS 智能问数，缺省 CS），路由到平台真实能力作答 */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        return Result.ok(csService.ask(body.get("question"), body.get("scene")));
    }

    /** 路由反馈（viewer 也可提交；错例回流进路由提示词） */
    @PostMapping("/feedback")
    public Result<CsFeedback> feedback(@RequestBody Map<String, Object> body) {
        Object correct = body.get("correct");
        Integer c = correct == null ? 0
                : (Boolean.parseBoolean(String.valueOf(correct)) || "1".equals(String.valueOf(correct)) ? 1 : 0);
        return Result.ok(csService.saveFeedback(
                body.get("question") == null ? null : String.valueOf(body.get("question")),
                body.get("intent") == null ? null : String.valueOf(body.get("intent")),
                body.get("router") == null ? null : String.valueOf(body.get("router")),
                c,
                body.get("comment") == null ? null : String.valueOf(body.get("comment"))));
    }

    /** 反馈列表（分页，管理查看） */
    @GetMapping("/feedback/list")
    public Result<PageResult<CsFeedback>> feedbackList(@RequestParam(required = false) Integer pageNum,
                                                       @RequestParam(required = false) Integer pageSize) {
        return Result.ok(csService.feedbackPage(
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }
}
