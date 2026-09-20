package com.study.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

// ============================================================
//  ★ 자리가 바뀌었다.
//
//  Phase 4 전체        우리가 남의 API 를 '부르는 쪽' 이었다
//  여기부터            기관 전자정부(Java)가 '우리 AI 제품' 을 부른다
//
//      기관 JSP 화면
//          ↓
//      기관 Java 백엔드   ← ★ 이 클래스가 그 자리다
//          ↓  HTTP
//      우리 FastAPI (aisvc)
//          ↓
//      답변 → JSP 렌더
//
//  이 코드는 '기관 개발자가 써온 것' 이라고 치자.
//  실제로 현장에서는 우리가 이 샘플을 써서 넘겨준다.
// ============================================================
@Service
public class EgovAiClient {

    private static final Logger log = LoggerFactory.getLogger(EgovAiClient.class);

    @Value("${ai.base-url}")
    private String aiBaseUrl;

    // 연결 3초 / 읽기 5초가 걸려 있는 그 템플릿
    @Resource
    private RestTemplate restTemplate;

    /** 실습용 질문 꾸러미. 브라우저가 먼저 가로채지 않게 번호로 고른다. */
    public static String 질문고르기(String preset) {
        return switch (preset) {
            case "2" -> "입항 & 출항 절차 알려줘";
            case "3" -> "사용료 100% 감면 문의";
            case "4" -> "선석 배정 기준은? 대기시간도 같이";
            default   -> "입항 절차 알려줘";
        };
    }

    /** 접속할 주소를 고른다. 실습용으로 세 가지를 준비해뒀다. */
    private String 주소(String target) {
        return switch (target) {
            // 서비스는 떠 있는데 포트를 잘못 적은 경우
            case "wrongport" -> aiBaseUrl.replace(":8000", ":9999");
            // 방화벽이 패킷을 버리는 경우 (통신이 안 되는 대역으로 보낸다)
            case "blocked"   -> "http://192.0.2.1:8000";
            default          -> aiBaseUrl;
        };
    }

    // ------------------------------------------------------------
    //  헬스체크 — 개통 확인용
    // ------------------------------------------------------------
    public Map<String, Object> health(String target) {
        String url = 주소(target) + "/ai/health";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("부른주소", url);

        long start = System.currentTimeMillis();
        try {
            String body = restTemplate.getForObject(url, String.class);
            out.put("결과", "성공");
            out.put("응답", body);
        } catch (ResourceAccessException e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("진짜원인", 뿌리(e).getClass().getName());
            out.put("원인메시지", String.valueOf(뿌리(e).getMessage()));
        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", String.valueOf(e.getMessage()));
        }
        out.put("걸린시간ms", System.currentTimeMillis() - start);
        return out;
    }

    // ------------------------------------------------------------
    //  질문 보내기 (GET)
    //
    //  기관 화면에서 사용자가 입력한 질문을 그대로 넘긴다.
    // ------------------------------------------------------------
    public Map<String, Object> ask(String question, String target) {
        String url = 주소(target) + "/ai/ask?q=" + question;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("사용자가입력한질문", question);
        out.put("부른주소", url);

        long start = System.currentTimeMillis();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.getForObject(url, Map.class);
            out.put("결과", "성공");
            out.put("AI응답", body);
        } catch (HttpClientErrorException e) {
            out.put("결과", "실패");
            out.put("상태코드", e.getStatusCode().value());
            out.put("상대가한말", 짧게(e.getResponseBodyAsString()));
        } catch (ResourceAccessException e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("진짜원인", 뿌리(e).getClass().getName());
            out.put("원인메시지", String.valueOf(뿌리(e).getMessage()));
        }
        out.put("걸린시간ms", System.currentTimeMillis() - start);
        return out;
    }

    // ------------------------------------------------------------
    //  질문 보내기 (POST)
    // ------------------------------------------------------------
    public Map<String, Object> askPost(String question, String target) {
        String url = 주소(target) + "/ai/ask";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("사용자가입력한질문", question);
        out.put("부른주소", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"question\":\"" + question + "\"}";
        HttpEntity<String> req = new HttpEntity<>(json, headers);

        long start = System.currentTimeMillis();
        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> res = restTemplate.exchange(url, HttpMethod.POST, req, Map.class);
            out.put("결과", "성공");
            out.put("AI응답", res.getBody());
        } catch (ResourceAccessException e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("진짜원인", 뿌리(e).getClass().getName());
            out.put("원인메시지", String.valueOf(뿌리(e).getMessage()));
        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", 짧게(String.valueOf(e.getMessage())));
        }
        out.put("걸린시간ms", System.currentTimeMillis() - start);
        return out;
    }

    private Throwable 뿌리(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    private String 짧게(String s) {
        if (s == null) return "-";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + " ...(생략)" : s;
    }
}
