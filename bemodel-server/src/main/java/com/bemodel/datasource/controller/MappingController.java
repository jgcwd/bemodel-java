package com.bemodel.datasource.controller;

import com.bemodel.common.Result;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.service.MappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mapping")
@RequiredArgsConstructor
public class MappingController {

    private final MappingService mappingService;

    @GetMapping("/list")
    public Result<List<Mapping>> list(@RequestParam(required = false) String dsCode,
                                      @RequestParam(required = false) String tableName) {
        return Result.ok(mappingService.list(dsCode, tableName));
    }

    @PostMapping("/batch")
    public Result<Void> saveBatch(@RequestBody List<Mapping> mappings) {
        mappingService.saveBatch(mappings);
        return Result.ok();
    }

    @GetMapping("/ai-suggest")
    public Result<Map<String, Object>> aiSuggest(@RequestParam String dsCode,
                                                 @RequestParam String tableName) {
        return Result.ok(mappingService.aiSuggest(dsCode, tableName));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mappingService.removeById(id);
        return Result.ok();
    }
}
