package com.codecritic.controller;

import com.codecritic.dto.*;
import com.codecritic.service.AnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing analysis endpoints used by the Python agent.
 */
@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/complexity")
    public ResponseEntity<ComplexityResponse> complexity(@RequestBody ComplexityRequest req) {
        if (req == null || req.code() == null) {
            return ResponseEntity.badRequest().build();
        }
        ComplexityResponse resp = analysisService.calculateComplexity(req.code());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/bugs")
    public ResponseEntity<BugReport> bugs(@RequestBody BugRequest req) {
        if (req == null || req.code() == null) {
            return ResponseEntity.badRequest().build();
        }
        BugReport report = analysisService.findBugs(req.code());
        return ResponseEntity.ok(report);
    }

    @PostMapping("/generate-test")
    public ResponseEntity<TestGenerationResponse> generateTest(@RequestBody TestGenerationRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().build();
        }
        TestGenerationResponse resp = analysisService.generateTest(
            req.className(), 
            req.methodName(), 
            req.parameters(), 
            req.code()
        );
        return ResponseEntity.ok(resp);
    }
}
