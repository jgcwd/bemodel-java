package com.bemodel.cs;

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

    /** 问一问：自然语言提问，路由到平台真实能力作答 */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        return Result.ok(csService.ask(body.get("question")));
    }
}
