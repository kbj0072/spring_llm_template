package com.example.llm.llm.dto;

import java.util.List;

public class ChatRequest {

    private List<Message> messages;

    public ChatRequest() {}

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public static class Message {
        private String role;
        // String(텍스트 전용) 또는 List<Map>(OpenAI vision 배열 포맷) 모두 허용
        private Object content;

        public Message() {}

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public Object getContent() {
            return content;
        }

        public void setContent(Object content) {
            this.content = content;
        }
    }
}
