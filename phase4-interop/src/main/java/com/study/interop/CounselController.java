package com.study.interop;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OO공단 민원 AI 상담.
 * 민원인이 상담창에 질문을 쓰면 우리 AI 에 묻고 답을 돌려준다.
 *
 * ★ 개인정보 (SFR-PII-04)
 *   민원인 질문에는 주민번호가 섞여 올 수 있다.
 *   질문은 MaskingUtil.maskText 로 가린 뒤에만 로그에 남기고 AI 로 보낸다.
 *
 * 작성 : 박사원 / 2026-09-23
 */
@RestController
@RequestMapping("/counsel")
public class CounselController {

    private static final Logger log = LoggerFactory.getLogger(CounselController.class);

    @Value("${ai.base-url}")
    private String aiBaseUrl;

    @Resource
    private RestTemplate restTemplate;

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> req) {
        String question = req.getOrDefault("question", "");

        // 개인정보 가리기 — 로그보다 먼저. 이 아래로는 원본(question)을 쓰지 않는다
        String maskedQuestion = MaskingUtil.maskText(question);
        log.info("[counsel] 접수 question={}", maskedQuestion);

        // ★ 가린 질문을 AI 로 보낸다
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", maskedQuestion);

        String answer;
        try {
            Map<?, ?> aiRes = restTemplate.postForObject(aiBaseUrl + "/ai/counsel", body, Map.class);
            answer = String.valueOf(aiRes.get("answer"));
        } catch (RestClientException e) {
            log.error("[counsel] AI 호출 실패 question={}", maskedQuestion, e);
            answer = "잠시 후 다시 시도해 주세요.";
        }

        log.info("[counsel] 완료 question={}", maskedQuestion);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("질문", maskedQuestion);
        out.put("답변", answer);
        return out;
    }
}
