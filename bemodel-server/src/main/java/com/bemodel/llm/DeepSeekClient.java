package com.bemodel.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * DeepSeek 大模型网关。所有 LLM 调用统一入口：
 * 超时可控、异常收敛为 Optional.empty（调用方据此走规则降级），
 * 每次调用记录审计日志（绑定调用时的本体发布版本），无 Key 时降级不断链。
 */
@Slf4j
@Component
public class DeepSeekClient {

    private final DeepSeekProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LlmLogService llmLogService;

    public DeepSeekClient(DeepSeekProperties props, ObjectMapper objectMapper,
                          @Lazy LlmLogService llmLogService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.llmLogService = llmLogService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getTimeoutSeconds() * 1000);
        factory.setReadTimeout(props.getTimeoutSeconds() * 1000);
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    public boolean enabled() {
        return props.enabled();
    }

    public String model() {
        return props.getModel();
    }

    public Optional<String> chat(String callType, String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();
        if (!props.enabled()) {
            log.warn("DeepSeek API Key 未配置，LLM 能力降级");
            llmLogService.log(callType, props.getModel(), digest(systemPrompt, userPrompt), 0, false, "API Key 未配置");
            return Optional.empty();
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("temperature", 0.2);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            String resp = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(resp);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            llmLogService.log(callType, props.getModel(), digest(systemPrompt, userPrompt),
                    System.currentTimeMillis() - start, content != null, null);
            return Optional.ofNullable(content);
        } catch (Exception e) {
            log.warn("DeepSeek 调用失败（将走降级逻辑）: {}", e.getMessage());
            llmLogService.log(callType, props.getModel(), digest(systemPrompt, userPrompt),
                    System.currentTimeMillis() - start, false, e.getMessage());
            return Optional.empty();
        }
    }

    private String digest(String systemPrompt, String userPrompt) {
        String s = (systemPrompt == null ? "" : systemPrompt) + " | "
                + (userPrompt == null ? "" : userPrompt);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
