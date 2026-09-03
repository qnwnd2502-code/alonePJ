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
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

// ============================================================
//  ★ 오늘의 주인공. 회사 연계 코드가 이 모양이다. ★
//
//  이름을 ○○Client 라고 짓는 게 관례다. 남의 시스템을 '부르는' 쪽이라는 뜻.
//  (○○Service = 우리 일을 한다 / ○○Client = 남의 시스템을 부른다)
//  회사 소스에서 XxxClient, XxxApiService, XxxConnector 같은 이름을 보면 연계 코드다.
// ============================================================
@Service
public class PartnerClient {

    private static final Logger log = LoggerFactory.getLogger(PartnerClient.class);

    // @Value = application.properties 의 값을 꺼내 넣는다.
    // 콜론 뒤는 기본값. 상대 주소와 키를 '소스에 박지 않는' 방법이다.
    @Value("${partner.base-url}")
    private String baseUrl;

    @Value("${partner.api-key}")
    private String apiKey;

    // ★ 오늘 추가. 같은 상대 기관인데 웹서버를 거쳐 https 로 들어가는 주소.
    //   값이 오는 길:  docker-compose.yml 의 PARTNER_TLS_BASE_URL
    //                -> application.properties 의 partner.tls-base-url
    //                -> 아래 @Value -> 이 필드
    @Value("${partner.tls-base-url}")
    private String tlsBaseUrl;

    @Resource
    private RestTemplate restTemplate;

    // 서명을 만들어주는 부품. 계산은 저기 맡기고 여기는 '실어 보내는 일' 만 한다.
    @Resource
    private HmacSigner hmacSigner;

    // ------------------------------------------------------------
    //  방식 1) API Key 를 URL 뒤에 붙여 보낸다 (공공데이터포털 방식)
    //
    //  ★ 이 방식의 문제: URL 은 로그에 남는다. https 를 써도 남는다.
    //    실습에서 상대 서버 로그를 직접 보고 확인한다.
    // ------------------------------------------------------------
    public Map<String, Object> fileListByUrlKey() {

        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/openapi/file/list")
                .queryParam("serviceKey", apiKey)
                .toUriString();

        log.debug("[연계요청] GET {}", mask(url));

        // getForObject = GET 을 쏘고 응답 본문만 받는다. 가장 단순한 형태
        return restTemplate.getForObject(url, Map.class);
    }

    // ------------------------------------------------------------
    //  방식 2) API Key 를 헤더에 실어 보낸다 (요즘 권장 방식)
    //
    //  헤더를 붙이려면 getForObject 로는 안 되고 exchange 를 써야 한다.
    //  회사 연계 코드가 대부분 이 4단계 모양이다:
    //    ① HttpHeaders 만들고  ② HttpEntity 로 감싸고
    //    ③ exchange 로 쏘고    ④ ResponseEntity 에서 꺼낸다
    // ------------------------------------------------------------
    public Map<String, Object> fileListByHeaderKey() {

        String url = baseUrl + "/openapi/file/list";

        // ① 헤더 만들기
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

        // ② 헤더를 봉투에 담는다. GET 이라 본문(body)은 없어서 null
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);

        log.debug("[연계요청] GET {} (키는 헤더에)", url);

        // ③ 쏜다
        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class);

        // ④ 꺼낸다. 상태코드도 같이 온다
        log.debug("[연계응답] status={}", response.getStatusCode());
        return response.getBody();


    }



    // ------------------------------------------------------------
    //  방식 3) 일부러 키를 빼고 부른다 -> 401 이 온다
    //
    //  ★ 오늘 자바 10분에서 배운 try-catch 가 여기서 쓰인다.
    //    연계는 남의 시스템이라 '반드시' 터진다. 안 터지는 게 이상한 것이다.
    // ------------------------------------------------------------
    public Map<String, Object> fileListWithoutKey() {

        String url = baseUrl + "/openapi/file/list";
        log.debug("[연계요청] GET {} (키 없음 - 일부러)", url);

        try {
            return restTemplate.getForObject(url, Map.class);

        } catch (HttpClientErrorException e) {
            // 4xx = '내 잘못'. 키가 틀렸거나 요청이 잘못됐다.
            // ★ 재시도해봐야 소용없다. 백번 해도 401 이다.
            log.warn("[연계실패] 4xx status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return Map.of(
                    "실패종류", "4xx (내 요청이 잘못됨)",
                    "status", e.getStatusCode().value(),
                    "상대응답", e.getResponseBodyAsString(),
                    "재시도해야하나", "아니오. 키/요청을 고쳐야 한다");

        } catch (HttpServerErrorException e) {
            // 5xx = '상대 잘못'. 상대 서버가 터졌거나 점검 중.
            // ★ 이건 재시도 대상이다.
            log.error("[연계실패] 5xx status={}", e.getStatusCode());
            return Map.of(
                    "실패종류", "5xx (상대 서버 문제)",
                    "status", e.getStatusCode().value(),
                    "재시도해야하나", "예");

        } catch (ResourceAccessException e) {
            // 아예 연결이 안 됨. 타임아웃, 서버 다운, 방화벽, DNS.
            // ★ HTTP 상태코드조차 없다. 응답이 아예 안 왔기 때문이다.
            log.error("[연계실패] 연결 자체가 안 됨: {}", e.getMessage());
            return Map.of(
                    "실패종류", "연결 실패 (타임아웃/서버다운/방화벽)",
                    "status", "없음 - 응답이 아예 안 왔다",
                    "재시도해야하나", "예. 단 멱등성 주의");
        }
        // ★ 여기서 catch (Exception e) { } 로 싸잡아 잡고 아무것도 안 하면
        //   '조용한 실패' 가 된다. 연계에서 제일 위험한 코드다.
    }

    // ------------------------------------------------------------
    //  파일 실물 내려받기.
    //  목록의 fileStreCours 는 '상대 서버의' 경로라 우리가 못 연다.
    //  파일을 실제로 가져오려면 상대가 열어준 이 창구를 써야 한다.
    // ------------------------------------------------------------
    public byte[] downloadFile(String atchFileId) {

        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/openapi/file/download")
                .queryParam("atchFileId", atchFileId)
                .queryParam("serviceKey", apiKey)
                .toUriString();

        log.debug("[연계요청] 파일 다운로드 {}", atchFileId);

        // byte[] 로 받는다. JSON 이 아니라 바이너리이기 때문이다.
        return restTemplate.getForObject(url, byte[].class);
    }

    // ------------------------------------------------------------
    //  ★ "헤더에 실었다" 를 눈으로 보기 위한 두 개. 학습 전용.
    //    상대 서버의 /openapi/echo 는 우리가 보낸 요청을 그대로 되돌려준다.
    //    위의 fileListByUrlKey / fileListByHeaderKey 와 '똑같은 방식' 으로 보낸다.
    // ------------------------------------------------------------
    public Map<String, Object> echoWithUrlKey() {

        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/openapi/echo")
                .queryParam("serviceKey", apiKey)      // <- 주소 뒤에 붙인다
                .toUriString();

        return restTemplate.getForObject(url, Map.class);
    }

    public Map<String, Object> echoWithHeaderKey() {

        String url = baseUrl + "/openapi/echo";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);   // <- 헤더 한 줄을 만든다
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Void> entity = new HttpEntity<>(null, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class);
        return response.getBody();
    }

    // ============================================================
    //  ★ 오늘의 주인공 — 같은 요청을 https 로 보낸다.
    //
    //  코드에서 달라지는 건 딱 한 글자다: http -> https
    //  나머지(헤더, exchange, 응답 꺼내기)는 1일차와 완전히 똑같다.
    //
    //  그런데 이게 실패한다. 왜 실패하는지가 오늘 수업의 전부다.
    // ============================================================
    public Map<String, Object> fileListOverTls() {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = tlsBaseUrl + "/openapi/file/list";
        log.debug("[연계요청-TLS] GET {}", url);   // 헤더 방식이라 URL 에 키가 없다

        ResponseEntity<Map> res = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class);

        return res.getBody();
    }

    // ============================================================
    //  ★ mTLS 확인용 — 상대 서버가 '우리를 누구로 봤는지' 되돌려받는다.
    //
    //  1일차의 echo 와 같은 창구인데, 이번엔 https 로 들어간다.
    //  달라지는 것: 응답의 '헤더전부' 안에 X-Client-Dn 이 생긴다.
    //  그 값은 우리가 보낸 게 아니다. 상대 기관 nginx 가
    //  '우리 인증서에서 읽어내서' 뒷단 앱에 붙여준 것이다.
    // ============================================================
    public Map<String, Object> echoOverTls() {

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = tlsBaseUrl + "/openapi/echo";
        log.debug("[연계요청-TLS] GET {}", url);

        ResponseEntity<Map> res = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class);

        return res.getBody();
    }

    // ============================================================
    //  ★ 오늘의 주인공 — 전문 위·변조 방지 서명을 실어 POST 로 보낸다.
    //
    //  지금까지는 GET 으로 '조회' 만 했다. 서명은 보통 '보내는 내용(본문)' 을
    //  지키는 것이라 POST 부터 의미가 있다.
    //
    //  ★ 4단계로 보면 1일차 헤더 인증과 구조가 똑같다:
    //    (1) 서명할 대상을 조립(canonical)  (2) 서명 계산  (3) 헤더에 싣기  (4) 보내기
    // ============================================================
    public Map<String, Object> registerWithSignature(String path, String body, boolean tamper, String fixedTimestamp) {

        // (1) 서명할 대상을 조립한다. 상대 코드와 순서가 같아야 한다.
        String timestamp = (fixedTimestamp != null)
                ? fixedTimestamp
                : String.valueOf(System.currentTimeMillis());

        String canonical = hmacSigner.canonical(timestamp, "POST", path, body);

        // (2) 서명을 계산한다.
        String signature = hmacSigner.sign(canonical);

        // ★ 변조 실험용: 서명은 위 본문으로 만들고, 실제로 보내는 본문만 바꾼다.
        //   중간 경유지가 내용을 바꿔치기한 상황을 흉내낸 것이다.
        String bodyToSend = tamper
                ? body.replace("민원신청서", "다른파일로바꿔치기")
                : body;

        // (3) 헤더에 싣는다. 본문은 평문 그대로다(HMAC 은 숨기지 않는다).
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("X-Timestamp", timestamp);
        headers.set("X-Signature", signature);

        HttpEntity<String> entity = new HttpEntity<>(bodyToSend, headers);

        String url = tlsBaseUrl + path;
        log.debug("[연계요청-서명] POST {}", url);
        log.debug("[서명대상] {}", canonical.replace("\n", " / "));   // 줄바꿈을 눈에 보이게
        log.debug("[서명값]   {}", signature);
        if (tamper) {
            log.debug("[변조]     본문을 바꿔서 보낸다: {}", bodyToSend);
        }

        // (4) 보낸다. 401 이면 HttpClientErrorException 이 튀므로 여기서 잡아 분류한다.
        try {
            ResponseEntity<Map> res = restTemplate.exchange(
                    url, HttpMethod.POST, entity, Map.class);
            return res.getBody();

        } catch (HttpClientErrorException e) {
            // 4xx = 우리 요청이 잘못된 것. 재시도해도 똑같다(1일차 실패 3분류).
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("결과", "거절당함 (4xx - 재시도 무의미)");
            out.put("상태코드", e.getStatusCode().value());
            // ★ 인코딩을 명시한다. 안 하면 스프링이 ISO-8859-1 로 읽어서
            //   상대가 보낸 한글 메시지가 깨진다. (오늘 자바 10분과 같은 함정)
            out.put("상대가한말", e.getResponseBodyAsString(java.nio.charset.StandardCharsets.UTF_8));
            return out;
        }
    }

    // 실습용 창구들. 본문은 같고 '무엇을 비틀었는지' 만 다르다.
    private static final String SAMPLE_BODY =
            "{\"atchFileId\":\"FILE_000000000000123\",\"orignlFileNm\":\"민원신청서.pdf\"}";

    /** 정상 서명 요청 */
    public Map<String, Object> hmacOk() {
        return registerWithSignature("/openapi/file/register", SAMPLE_BODY, false, null);
    }

    // ★ 인코딩 실험용 — 본문에 '한글이 없는' 요청.
    //   인코딩이 틀렸을 때 hmac-ok(한글 있음)는 실패하고 이건 성공한다.
    //   그 차이가 곧 "인코딩 문제다" 라는 진단이다.
    private static final String ASCII_BODY =
            "{\"atchFileId\":\"FILE_000000000000123\",\"orignlFileNm\":\"report.pdf\"}";

    /** 한글 없는 본문으로 정상 서명 요청 */
    public Map<String, Object> hmacAscii() {
        return registerWithSignature("/openapi/file/register", ASCII_BODY, false, null);
    }

    /** 서명은 원본으로 만들고 본문만 바꿔치기 -> 상대가 잡아내야 한다 */
    public Map<String, Object> hmacTampered() {
        return registerWithSignature("/openapi/file/register", SAMPLE_BODY, true, null);
    }

    /** 10분 전 timestamp 로 서명 -> 서명 자체는 유효하다. v1 은 통과, v2 는 거절 */
    public Map<String, Object> hmacOld(String path) {
        String old = String.valueOf(System.currentTimeMillis() - 600_000L);
        return registerWithSignature(path, SAMPLE_BODY, false, old);
    }

    // ------------------------------------------------------------
    //  로그에 찍기 전에 키를 가린다. 실무에서 반드시 있어야 하는 코드.
    //  앞 4글자만 남기는 이유: 어떤 키를 썼는지 구분은 돼야 장애 추적이 된다.
    //  ("A키 썼는데 401" 인지 "B키 썼는데 401" 인지 알아야 하므로)
    //
    //  ★ 2026-08-29 여기서 컴파일 에러를 냈다. 원인 2개:
    //    1) 이 메서드를 다른 메서드 '안' 에 넣어서 중괄호가 어긋남
    //       -> illegal start of expression (자바에서 이 문구는 거의 항상 중괄호 문제)
    //    2) api.apiKey 오타 (apiKey 가 맞다)
    //  ★ 자바 컴파일 에러는 '첫 번째' 것만 본다. 뒤에 줄줄이 딸려 나오는 건 여파다.
    // ------------------------------------------------------------
    private String mask(String text) {
        if (text == null || apiKey == null || apiKey.length() < 4) {
            return text;
        }
        return text.replace(apiKey, apiKey.substring(0, 4) + "****(가림)");
    }
}
