package com.study.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ============================================================
//  장애를 견디는 부품.  (실습 6)
//
//  회사 소스에서 이런 이름으로 있다 :
//    RetryTemplate, XxxApiClient 안의 for 루프, HttpUtil.callWithRetry ...
//
//  ★ 오늘 배우는 세 가지가 여기 다 들어있다.
//     1) 타임아웃  - 언제 포기할지
//     2) 재시도    - 무엇을 다시 보낼지 (그리고 무엇은 보내면 안 되는지)
//     3) 멱등성 키 - 다시 보내도 안전하게 만드는 법
// ============================================================
@Service
public class ResilientClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientClient.class);

    @Value("${partner.base-url}")
    private String baseUrl;

    // 정상 템플릿. InteropApplication 에서 만든 것 - 연결3초 / 읽기5초 가 걸려 있다.
    @Resource
    private RestTemplate restTemplate;

    // ★ 사고 재현용 템플릿. 타임아웃을 '설정하지 않은' RestTemplate.
    //   new RestTemplate() 은 회사 소스에서 제일 흔히 보이는 모양이고,
    //   그 기본값이 곧 '무제한' 이다. 0 = 즉시포기 가 아니라 0 = 영원히 기다림.
    private final RestTemplate noTimeout = new RestTemplate();

    // ------------------------------------------------------------
    //  (1) 타임아웃 : 상대가 느릴 때
    // ------------------------------------------------------------

    /**
     * 느린 상대를 부른다.
     *
     * 값의 흐름 :
     *   sec --> partner:8000/openapi/slow?sec=N --> 상대가 N초 잔다
     *                                          --> 우리 readTimeout(5초) 이 먼저 끝나면
     *                                              ResourceAccessException 이 터진다
     *
     * ★ 이 예외에는 HTTP 상태코드가 없다. 상대는 아직 아무 말도 안 했다.
     *   "응답 코드 몇 번이었어요?" 라고 물으면 답이 없는 게 정상이다.
     */
    public Map<String, Object> callSlow(int sec, boolean useTimeout) {
        RestTemplate rt = useTimeout ? restTemplate : noTimeout;
        String url = baseUrl + "/openapi/slow?sec=" + sec;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("호출한주소", url);
        out.put("쓴템플릿", useTimeout ? "타임아웃 있음 (연결3초/읽기5초)" : "타임아웃 없음 (무제한)");

        long start = System.currentTimeMillis();
        try {
            String body = rt.getForObject(url, String.class);
            out.put("결과", "성공");
            out.put("응답", body);
        } catch (ResourceAccessException e) {
            // ★ 연결 타임아웃과 읽기 타임아웃이 둘 다 이 예외로 온다.
            //   어느 쪽인지는 안쪽 원인(cause)을 봐야 안다.
            out.put("결과", "타임아웃");
            out.put("예외", e.getClass().getSimpleName());
            out.put("진짜원인", e.getCause() == null ? "-" : e.getCause().toString());
        }
        out.put("걸린시간ms", System.currentTimeMillis() - start);
        return out;
    }

    /** 외부 호출이 전혀 없는 창구. 스레드가 남아있는지 확인하는 데 쓴다. */
    public Map<String, Object> ping() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("결과", "살아있음");
        out.put("처리한스레드", Thread.currentThread().getName());
        return out;
    }

    // ------------------------------------------------------------
    //  (2) 재시도 : 상대가 가끔 실패할 때
    // ------------------------------------------------------------

    /** 재시도 없이 한 번만 부른다. 상대가 500 이면 그대로 실패. */
    public Map<String, Object> flakyOnce() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("결과", "성공");
            out.put("응답", restTemplate.getForObject(baseUrl + "/openapi/flaky", String.class));
        } catch (HttpServerErrorException e) {
            out.put("결과", "실패");
            out.put("상태코드", e.getStatusCode().value());
            out.put("상대가한말", e.getResponseBodyAsString());
        }
        return out;
    }

    /**
     * 재시도하며 부른다. 지수 백오프(간격을 2배씩 늘림).
     *
     * ★ 오늘의 핵심 규칙 - 무엇을 재시도할 것인가
     *     5xx      : 상대 서버 잘못  -> 다시 보내면 될 수도 있다.  재시도 O
     *     타임아웃  : 상대가 느림    -> 다시 보내면 될 수도 있다.  재시도 O (단, 조건부)
     *     4xx      : 우리 요청 잘못  -> 백 번 보내도 똑같다.       재시도 X
     *
     *   4xx 를 재시도하는 코드는 상대 기관 서버만 두들기고 결과는 같다.
     *   회사 소스에서 catch (Exception e) 로 뭉뚱그려 재시도하는 걸 보면 여기를 의심할 것.
     */
    public Map<String, Object> flakyWithRetry(int maxAttempts) {
        List<String> 시도로그 = new ArrayList<>();
        long delayMs = 200;                       // 첫 대기 200ms

        for (int i = 1; i <= maxAttempts; i++) {
            try {
                String body = restTemplate.getForObject(baseUrl + "/openapi/flaky", String.class);
                시도로그.add(i + "회차 : 성공");

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("결과", "성공");
                out.put("시도횟수", i);
                out.put("시도로그", 시도로그);
                out.put("응답", body);
                return out;

            } catch (HttpClientErrorException e) {
                // ★ 4xx. 여기서 즉시 포기하는 것이 옳다.
                시도로그.add(i + "회차 : " + e.getStatusCode().value() + " (4xx 는 재시도하지 않는다)");

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("결과", "포기");
                out.put("사유", "우리 요청이 잘못됐다. 다시 보내도 같다.");
                out.put("시도로그", 시도로그);
                return out;

            } catch (HttpServerErrorException | ResourceAccessException e) {
                // 5xx 또는 타임아웃. 재시도 대상.
                String 무엇 = (e instanceof HttpServerErrorException he)
                        ? String.valueOf(he.getStatusCode().value())
                        : "타임아웃";

                if (i == maxAttempts) {
                    시도로그.add(i + "회차 : " + 무엇 + " -> 마지막 시도였다. 포기");
                    break;
                }
                시도로그.add(i + "회차 : " + 무엇 + " -> " + delayMs + "ms 뒤 재시도");
                sleep(delayMs);
                delayMs *= 2;                     // ★ 지수 백오프
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("결과", "최종실패");
        out.put("시도로그", 시도로그);
        return out;
    }

    // ------------------------------------------------------------
    //  (3) 멱등성 : 재시도가 위험해지는 순간
    // ------------------------------------------------------------

    /**
     * 같은 결제를 times 번 보낸다. (응답을 못 받아서 재시도했다고 치자)
     *
     * useIdemKey 가 true 면 Idempotency-Key 를 붙인다. 세 번 다 '같은 키' 다.
     *
     * ★ 여기가 오늘의 결론이다.
     *   키가 없으면 : 3번 보냄 -> 상대 원장에 거래 3건.  결제가 3번 됐다.
     *   키가 있으면 : 3번 보냄 -> 상대 원장에 거래 1건.  나머지 2번은 첫 결과를 돌려받는다.
     *
     *   ★★ 키는 '재시도할 때마다' 가 아니라 '업무 1건당' 하나다.
     *      재시도마다 새 키를 만들면 키가 없는 것과 똑같다. 가장 흔한 구현 실수다.
     *      그래서 아래 UUID 생성이 for 루프 '밖에' 있다. 이 위치가 전부다.
     */
    public Map<String, Object> pay(String 주문번호, int 금액, int times, boolean useIdemKey) {
        // ★ 루프 밖에서 한 번만 만든다.
        String idemKey = UUID.randomUUID().toString();

        List<Object> 응답들 = new ArrayList<>();
        for (int i = 1; i <= times; i++) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (useIdemKey) {
                headers.set("Idempotency-Key", idemKey);
            }

            String json = "{\"주문번호\":\"" + 주문번호 + "\",\"금액\":" + 금액 + "}";
            HttpEntity<String> req = new HttpEntity<>(json, headers);

            ResponseEntity<String> res = restTemplate.exchange(
                    baseUrl + "/openapi/payment", HttpMethod.POST, req, String.class);
            응답들.add(i + "번째 -> " + res.getBody());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("보낸횟수", times);
        out.put("멱등성키", useIdemKey ? idemKey : "안 붙임");
        out.put("응답들", 응답들);
        out.put("확인방법", "/interop/payments 를 열어 원장에 몇 건 쌓였는지 볼 것");
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> payments() {
        return restTemplate.getForObject(baseUrl + "/openapi/payments", Map.class);
    }

    public Map<String, Object> resetAll() {
        restTemplate.postForObject(baseUrl + "/openapi/payments/reset", null, Map.class);
        restTemplate.postForObject(baseUrl + "/openapi/flaky/reset", null, Map.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("결과", "상대 기관 원장과 실패카운터를 초기화했다");
        return out;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // ★ 인터럽트를 삼키지 말 것. 다시 세워두는 게 자바 관례다.
            Thread.currentThread().interrupt();
        }
    }
}
