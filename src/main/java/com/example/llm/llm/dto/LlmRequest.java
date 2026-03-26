package com.example.llm.llm.dto;

import java.util.List;

public class LlmRequest {

    private String model;
    private List<ChatRequest.Message> messages;

    public LlmRequest() {}

    public LlmRequest(String model, List<ChatRequest.Message> messages) {
        this.model = model;
        this.messages = messages;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatRequest.Message> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatRequest.Message> messages) {
        this.messages = messages;
    }
}
