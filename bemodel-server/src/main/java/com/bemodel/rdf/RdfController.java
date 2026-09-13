package com.bemodel.rdf;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/rdf")
@RequiredArgsConstructor
public class RdfController {

    private final RdfService rdfService;

    /** 导出患者 ABox（Turtle），供处方审核原型加载 */
    @GetMapping("/patient/{inhosNo}")
    public ResponseEntity<String> exportPatient(@PathVariable String inhosNo) {
        String turtle = rdfService.exportPatient(inhosNo);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "turtle", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", inhosNo + ".ttl");
        return new ResponseEntity<>(turtle, headers, 200);
    }
}
