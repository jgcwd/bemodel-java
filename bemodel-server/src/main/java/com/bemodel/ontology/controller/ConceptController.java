package com.bemodel.ontology.controller;

import com.bemodel.common.Result;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.service.ConceptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/concept")
@RequiredArgsConstructor
public class ConceptController {

    private final ConceptService conceptService;
    private final com.bemodel.ontology.service.RelationService relationService;
    private final com.bemodel.ontology.mapper.AttributeMapper attributeMapper;
    private final com.bemodel.ontology.mapper.RelationMapper relationMapper;

    @GetMapping("/list")
    public Result<List<Concept>> list(@RequestParam(required = false) String domainCode) {
        return Result.ok(conceptService.listByDomain(domainCode));
    }

    @GetMapping("/detail/{code}")
    public Result<Map<String, Object>> detail(@PathVariable String code) {
        return Result.ok(conceptService.detail(code));
    }

    @PostMapping
    public Result<Concept> create(@RequestBody Concept concept) {
        return Result.ok(conceptService.create(concept));
    }

    @PutMapping
    public Result<Concept> update(@RequestBody Concept concept) {
        conceptService.updateById(concept);
        return Result.ok(concept);
    }

    @PostMapping("/transition/{code}")
    public Result<Concept> transition(@PathVariable String code, @RequestParam String target) {
        return Result.ok(conceptService.transition(code, target));
    }

    @DeleteMapping("/{code}")
    public Result<Void> delete(@PathVariable String code) {
        conceptService.deleteDraft(code);
        return Result.ok();
    }

    @PostMapping("/attribute")
    public Result<Attribute> addAttribute(@RequestBody Attribute attribute) {
        attributeMapper.insert(attribute);
        return Result.ok(attribute);
    }

    @DeleteMapping("/attribute/{id}")
    public Result<Void> deleteAttribute(@PathVariable Long id) {
        attributeMapper.deleteById(id);
        return Result.ok();
    }

    @PostMapping("/relation")
    public Result<Relation> addRelation(@RequestBody Relation relation) {
        return Result.ok(relationService.create(relation));
    }

    @PutMapping("/relation")
    public Result<Relation> updateRelation(@RequestBody Relation relation) {
        return Result.ok(relationService.update(relation));
    }

    @DeleteMapping("/relation/{id}")
    public Result<Void> deleteRelation(@PathVariable Long id) {
        relationMapper.deleteById(id);
        return Result.ok();
    }
}
