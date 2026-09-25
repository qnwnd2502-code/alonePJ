package com.study.interop;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OO공단 포털 - AI 검색 연계.
 * 포털 화면의 AI 검색창에서 호출한다.
 *
 * 작성 : OO공단 정보화팀 / 2026-09-20
 */
@RestController
@RequestMapping("/portal")
public class PortalController {

    private static final Logger log = LoggerFactory.getLogger(PortalController.class);

    @Value("${ai.base-url}")
    private String aiBaseUrl;

    @Resource
    private RestTemplate restTemplate;

    /** 로그인. (실습용으로 비밀번호 확인은 생략한다) */
    @GetMapping("/login")
    public Map<String, Object> login(@RequestParam String user, HttpSession session) {
        session.setAttribute("loginUser", user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("결과", "로그인");
        out.put("로그인사용자", user);
        return out;
    }

    @GetMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("결과", "로그아웃");
        return out;
    }

    /**
     * AI 검색.
     * 화면의 검색 폼이 검색어(q)와 사용자 ID(userId)를 함께 넘긴다.
     */
    @GetMapping("/ai-search")
    public Map<String, Object> aiSearch(
        @RequestParam(defaultValue = "") String q,
        HttpSession session) {

        
        Object loginUser = session.getAttribute("loginUser");
        if(loginUser == null){
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");   // 500(서버 고장)이 아니라 401(인증 필요)
        }

        String userId = extractUserId(loginUser);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);

        String url = UriComponentsBuilder
                .fromHttpUrl(aiBaseUrl + "/ai/search")
                .queryParam("q", q)
                .build()
                .encode()
                .toUriString();
        
        Map<String, Object> out = new LinkedHashMap<>();
        
        try {
            ResponseEntity<Map<String, Object>> res = restTemplate.exchange(
            url, 
            HttpMethod.GET, 
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
            );
        
            out.put("AI응답", res.getBody());
        } catch (RestClientException e) {
        // AI 서버 오류 발생 시
            log.error("AI 검색 실패: userId={}, q={}", userId, q, e);
            out.put("AI응답", Map.of("error", "AI 서버 연결 실패"));
        }
    
    // ─────────────────────────────────────────
    // 7. 세션 정보는 최소한만 반환 (개인정보 보호)
    // ─────────────────────────────────────────
        out.put("사용자", Map.of("userId", userId));
        
        // ─────────────────────────────────────────
        // 8. Map 반환 (JSON으로 자동 변환됨)
        // ─────────────────────────────────────────
        return out;
    }

    /**
     * 로그인 사용자 객체에서 userId 추출
     */
    private String extractUserId(Object loginUser) {
        if (loginUser instanceof Map) {
            // Map이면 "userId" 키로 꺼내기
            return (String) ((Map<?, ?>) loginUser).get("userId");
        }
        // Map이 아니면 toString
        return loginUser.toString();
    }
}
