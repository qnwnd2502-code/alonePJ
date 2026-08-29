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

    @Resource
    private RestTemplate restTemplate;

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
