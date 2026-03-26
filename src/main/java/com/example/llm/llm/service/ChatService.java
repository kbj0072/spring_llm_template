package com.example.llm.llm.service;

import com.example.llm.llm.dto.ChatRequest;
import com.example.llm.llm.dto.ChatResponse;
import com.example.llm.llm.dto.LlmRequest;
import com.example.llm.llm.dto.LlmResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class ChatService {

    private final RestTemplate restTemplate;

    @Value("${llm.api.base-url}")
    private String baseUrl;

    @Value("${llm.api.key}")
    private String apiKey;

    @Value("${llm.api.model}")
    private String model;

    public ChatService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ChatResponse chat(List<ChatRequest.Message> messages) {
        String url = baseUrl + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        LlmRequest llmRequest = new LlmRequest(model, messages);
        HttpEntity<LlmRequest> entity = new HttpEntity<>(llmRequest, headers);

        LlmResponse llmResponse = restTemplate.postForObject(url, entity, LlmResponse.class);

        if (llmResponse == null
                || llmResponse.getChoices() == null
                || llmResponse.getChoices().isEmpty()) {
            return new ChatResponse("응답을 받지 못했습니다.");
        }

        String content = llmResponse.getChoices().get(0).getMessage().getContent();
        return new ChatResponse(content);
    }
}
