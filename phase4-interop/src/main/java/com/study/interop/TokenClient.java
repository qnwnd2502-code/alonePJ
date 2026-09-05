package com.study.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

// ============================================================
//  토큰을 발급받고, 받은 토큰을 열어보는 부품.
//
//  회사 소스에서는 TokenManager, AuthClient, OAuth2Client 같은 이름으로 있다.
//  ★ 연계 규격서에 '인증: OAuth2' 나 '토큰 발급 URL' 이 있으면 이런 클래스가 하나 있다.
// ============================================================
@Service
public class TokenClient {

    private static final Logger log = LoggerFactory.getLogger(TokenClient.class);

    @Value("${partner.tls-base-url}")
    private String tlsBaseUrl;

    // 상대 기관이 발급해준 자격증명. API Key 와 성격이 같다(비밀값).
    @Value("${partner.client-id}")
    private String clientId;

    @Value("${partner.client-secret}")
    private String clientSecret;

    @Resource
    private RestTemplate restTemplate;

    // ------------------------------------------------------------
    //  ① 토큰 발급받기 (OAuth2 client_credentials)
    //
    //  ★ 여기가 실무에서 제일 많이 틀리는 지점이다.
    //    다른 API 는 전부 JSON 인데 토큰 창구만 form 형식이다.
    //    OAuth2 표준(RFC 6749)이 그렇게 정했다. JSON 을 보내면 400 이 난다.
    //
    //    JSON 방식 :  HttpEntity<String>              + APPLICATION_JSON
    //    form 방식 :  HttpEntity<MultiValueMap<...>>  + APPLICATION_FORM_URLENCODED
    //                 ↑ 이것
    // ------------------------------------------------------------
    public Map<String, Object> issueToken(String alg) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        // MultiValueMap = 키 하나에 값이 여러 개 올 수 있는 Map.
        // form 은 같은 이름의 항목이 여러 번 올 수 있어서 이 타입을 쓴다.
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type",    "client_credentials");
        form.add("client_id",     clientId);
        form.add("client_secret", clientSecret);
        form.add("alg",           alg);   // 학습용. 실제 규격엔 없다.

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        String url = tlsBaseUrl + "/oauth2/token";
        log.debug("[토큰요청] POST {} (alg={})", url, alg);

        ResponseEntity<Map> res = restTemplate.exchange(
                url, HttpMethod.POST, entity, Map.class);

        return res.getBody();
    }

    // ------------------------------------------------------------
    //  ② 토큰 안을 열어본다.
    //
    //  🚨 이건 '검증' 이 아니다. 그냥 읽는 것이다.
    //    비밀키도 공개키도 없이 누구나 할 수 있다. 서명은 확인하지 않는다.
    //    -> 그래서 JWT 안에 비밀번호나 주민번호를 넣으면 안 된다.
    //
    //  ★ 검증은 '받는 쪽' 이 한다. 우리는 발급받아 쓰는 쪽이라 열어보기만 한다.
    //    (회사 소스에서 토큰을 열어보는 코드를 '검증' 이라고 착각하지 말 것)
    // ------------------------------------------------------------
    public Map<String, Object> peek(String token) {

        Map<String, Object> out = new LinkedHashMap<>();

        // JWT 는 점(.) 두 개로 세 조각이다:  헤더.내용.서명
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            out.put("오류", "JWT 형식이 아님 (점으로 나뉜 3조각이어야 함)");
            return out;
        }

        out.put("1_헤더_원문", parts[0]);
        out.put("2_내용_원문", parts[1]);
        out.put("3_서명",     parts[2].substring(0, Math.min(24, parts[2].length())) + "...");

        // ★ getUrlDecoder 를 쓴다. getDecoder 가 아니다.
        //   JWT 는 URL-safe Base64 라서 + 대신 -, / 대신 _ 를 쓴다.
        //   표준 디코더로도 '대부분' 성공해서, 어쩌다 한 번만 터진다.
        //   -> "토큰 디코딩이 가끔 실패해요" 의 정체.
        Base64.Decoder dec = Base64.getUrlDecoder();

        out.put("1_헤더_해독", new String(dec.decode(parts[0]), StandardCharsets.UTF_8));
        out.put("2_내용_해독", new String(dec.decode(parts[1]), StandardCharsets.UTF_8));
        out.put("설명", "★ 비밀키 없이 읽었다. Base64 는 암호가 아니라 글자 바꾸기다.");

        return out;
    }

    // ------------------------------------------------------------
    //  ★ 만료 실험용 — 이미 만료된 토큰을 받아온다(학습 전용 창구).
    //    서명은 정상이고 exp 만 과거다. 서명 검증과 만료 검증은 별개다.
    // ------------------------------------------------------------
    public String issueExpiredToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity =
                new HttpEntity<>(new LinkedMultiValueMap<>(), headers);

        ResponseEntity<Map> res = restTemplate.exchange(
                tlsBaseUrl + "/oauth2/token-expired", HttpMethod.POST, entity, Map.class);

        return (String) res.getBody().get("access_token");
    }

    // ------------------------------------------------------------
    //  ★ 변조 실험 — 내용(2번째 조각)만 바꾸고 서명은 그대로 둔다.
    //
    //  공격자가 할 수 있는 일과 할 수 없는 일을 나눠 보는 것이 목적이다.
    //    할 수 있다 : 내용을 열어보고, 바꾸고, 다시 Base64 로 인코딩하기
    //    할 수 없다 : 바꾼 내용에 맞는 서명을 다시 만들기 (열쇠가 없으니까)
    // ------------------------------------------------------------
    public String tamper(String token, String from, String to) {

        String[] parts = token.split("\\.");

        Base64.Decoder dec = Base64.getUrlDecoder();
        // ★ withoutPadding() — JWT 는 끝의 = 패딩을 뺀다. 안 빼면 형식이 어긋난다.
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();

        String payload = new String(dec.decode(parts[1]), StandardCharsets.UTF_8);
        String changed = payload.replace(from, to);

        log.debug("[변조] {} -> {}", payload, changed);

        // 헤더와 서명은 그대로, 내용만 갈아끼운다.
        return parts[0] + "." + enc.encodeToString(changed.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
    }

    // ------------------------------------------------------------
    //  4xx 를 사람이 읽을 형태로 바꿔주는 공통 처리.
    //  (PartnerClient 에 있는 것과 같은 방식. 상대의 거절 이유를 봐야 하므로)
    // ------------------------------------------------------------
    public Map<String, Object> callAndReport(String token) {
        try {
            return callWithToken(token);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("결과", "거절당함 (4xx - 재시도 무의미)");
            out.put("상태코드", e.getStatusCode().value());
            out.put("상대가한말", e.getResponseBodyAsString(StandardCharsets.UTF_8));
            return out;
        }
    }

    // ------------------------------------------------------------
    //  ③ 받은 토큰으로 실제 API 를 부른다.
    //
    //  헤더 이름이 API Key 때와 똑같은 Authorization 이다.
    //  달라진 건 '무엇을 싣느냐' 뿐이다.
    //    API Key 방식 : Authorization: Bearer eGov-DEMO-KEY-...   <- 원본 비밀값
    //    토큰 방식    : Authorization: Bearer eyJhbGci...          <- 5분짜리 임시 신분증
    // ------------------------------------------------------------
    public Map<String, Object> callWithToken(String token) {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = tlsBaseUrl + "/openapi/file/list-jwt";
        log.debug("[연계요청-JWT] GET {}", url);

        ResponseEntity<Map> res = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class);

        return res.getBody();
    }
}
