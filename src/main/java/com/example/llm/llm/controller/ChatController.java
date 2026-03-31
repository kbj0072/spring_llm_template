package com.example.llm.llm.controller;

import com.example.llm.llm.dto.ChatRequest;
import com.example.llm.llm.dto.ChatResponse;
import com.example.llm.llm.service.ChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class ChatController {

    private static final String AUTH_SESSION_KEY = "chat-authenticated";
    private static final long LOGIN_WINDOW_MILLIS = 60_000L;

    private final ChatService chatService;
    private final ConcurrentHashMap<String, AttemptWindow> ipAttemptMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AttemptWindow> sessionAttemptMap = new ConcurrentHashMap<>();

    @Value("${chat.access.password-sha256}")
    private String passwordSha256;

    @Value("${chat.access.login.max-attempts-per-minute:5}")
    private int maxAttemptsPerMinute;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/")
    public String index(HttpSession session) {
        if (!isAuthenticated(session)) {
            return "redirect:/login";
        }
        return "chat";
    }

    @GetMapping("/login")
    public String loginPage(HttpSession session) {
        if (isAuthenticated(session)) {
            return "redirect:/";
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(
            @RequestParam("password") String password,
            HttpSession session,
            HttpServletRequest request,
            Model model) {
        long now = System.currentTimeMillis();
        String ipKey = resolveClientIp(request);
        String sessionKey = session.getId();

        if (isRateLimited(ipAttemptMap, ipKey, now) || isRateLimited(sessionAttemptMap, sessionKey, now)) {
            int retryAfterSeconds = Math.max(
                    getRetryAfterSeconds(ipAttemptMap, ipKey, now),
                    getRetryAfterSeconds(sessionAttemptMap, sessionKey, now));
            model.addAttribute("error", "로그인 시도가 너무 많습니다. " + retryAfterSeconds + "초 후 다시 시도하세요.");
            return "login";
        }

        if (hash(password).equalsIgnoreCase(passwordSha256)) {
            session.setAttribute(AUTH_SESSION_KEY, true);
            clearAttempts(ipAttemptMap, ipKey);
            clearAttempts(sessionAttemptMap, sessionKey);
            return "redirect:/";
        }

        recordFailedAttempt(ipAttemptMap, ipKey, now);
        recordFailedAttempt(sessionAttemptMap, sessionKey, now);

        model.addAttribute("error", "비밀번호가 올바르지 않습니다.");
        return "login";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @PostMapping("/api/extract-pdf")
    @ResponseBody
    public ResponseEntity<Map<String, String>> extractPdf(
            @RequestBody Map<String, String> body,
            HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String dataUrl = body.get("dataUrl");
        if (dataUrl == null || !dataUrl.startsWith("data:application/pdf;base64,")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "유효한 PDF data URL이 아닙니다."));
        }

        try {
            String b64 = dataUrl.substring("data:application/pdf;base64,".length());
            byte[] pdfBytes = Base64.getDecoder().decode(b64);
            try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
                String text = new PDFTextStripper().getText(doc).strip();
                if (text.isEmpty()) {
                    text = "(PDF에서 텍스트를 추출할 수 없었습니다. 스캔본 PDF일 수 있습니다.)";
                }
                return ResponseEntity.ok(Map.of("text", text));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "base64 디코딩에 실패했습니다."));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PDF 파싱 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request, HttpSession session) {
        if (!isAuthenticated(session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ChatResponse("로그인이 필요합니다."));
        }

        ChatResponse response = chatService.chat(request.getMessages());
        return ResponseEntity.ok(response);
    }

    private boolean isAuthenticated(HttpSession session) {
        Object authenticated = session.getAttribute(AUTH_SESSION_KEY);
        return Boolean.TRUE.equals(authenticated);
    }

    private boolean isRateLimited(ConcurrentHashMap<String, AttemptWindow> attemptMap, String key, long now) {
        AttemptWindow window = attemptMap.computeIfAbsent(key, k -> new AttemptWindow(now));
        synchronized (window) {
            if (isWindowExpired(window, now)) {
                resetWindow(window, now);
            }
            return window.attempts >= maxAttemptsPerMinute;
        }
    }

    private int getRetryAfterSeconds(ConcurrentHashMap<String, AttemptWindow> attemptMap, String key, long now) {
        AttemptWindow window = attemptMap.get(key);
        if (window == null) {
            return 1;
        }

        synchronized (window) {
            if (isWindowExpired(window, now)) {
                return 1;
            }
            long remainingMillis = LOGIN_WINDOW_MILLIS - (now - window.windowStartMillis);
            return (int) Math.max(1, Math.ceil(remainingMillis / 1000.0));
        }
    }

    private void recordFailedAttempt(ConcurrentHashMap<String, AttemptWindow> attemptMap, String key, long now) {
        AttemptWindow window = attemptMap.computeIfAbsent(key, k -> new AttemptWindow(now));
        synchronized (window) {
            if (isWindowExpired(window, now)) {
                resetWindow(window, now);
            }
            window.attempts++;
        }
    }

    private void clearAttempts(ConcurrentHashMap<String, AttemptWindow> attemptMap, String key) {
        attemptMap.remove(key);
    }

    private boolean isWindowExpired(AttemptWindow window, long now) {
        return now - window.windowStartMillis >= LOGIN_WINDOW_MILLIS;
    }

    private void resetWindow(AttemptWindow window, long now) {
        window.windowStartMillis = now;
        window.attempts = 0;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int commaIndex = forwardedFor.indexOf(',');
            if (commaIndex > -1) {
                return forwardedFor.substring(0, commaIndex).trim();
            }
            return forwardedFor.trim();
        }
        return request.getRemoteAddr();
    }

    private String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    private static class AttemptWindow {
        private long windowStartMillis;
        private int attempts;

        private AttemptWindow(long now) {
            this.windowStartMillis = now;
            this.attempts = 0;
        }
    }
}
