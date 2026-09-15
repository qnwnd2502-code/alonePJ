package com.study.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ============================================================
//  문서 수집기.  (실습 9)
//
//  기관 문서저장소에서 문서 목록을 받아 우리 색인에 넣는다.
//
//  수집 API 는 목록을 한 번에 다 주지 않는다.
//  offset / limit 으로 나눠서 받아야 한다. (페이징)
//  그리고 두 번째부터는 since 를 보내 '그 이후 것' 만 받는다. (증분 수집)
//
//  회사/발주처 소스에서 이런 이름으로 있다 :
//    XxxCrawler, XxxCollector, ContentSyncJob, IndexingBatch ...
// ============================================================
@Service
public class DocCollector {

    private static final Logger log = LoggerFactory.getLogger(DocCollector.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 한 번에 받아오는 건수. 상대 API 권장값. */
    private static final int LIMIT = 10;

    @Value("${partner.base-url}")
    private String baseUrl;

    @Resource
    private RestTemplate restTemplate;

    /** 우리 색인. 실무에서는 Elasticsearch / 벡터DB 다. */
    private final List<Map<String, Object>> 색인 = new ArrayList<>();

    /** 증분 수집 커서. 다음 수집 때 이 시각 이후 것만 받는다. */
    private volatile String 마지막수집시각 = "";

    /** 마지막 수집의 시작/종료 시각. 확인용으로 남겨둔다. */
    private volatile String 지난수집시작 = "";
    private volatile String 지난수집종료 = "";

    // ------------------------------------------------------------
    //  수집
    // ------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> run() {
        LocalDateTime 시작 = LocalDateTime.now().withNano(0);
        지난수집시작 = 시작.format(FMT);

        List<String> 호출로그 = new ArrayList<>();
        List<Map<String, Object>> 모은것 = new ArrayList<>();

        int offset = 0;
        while (true) {
            String url = baseUrl + "/openapi/docs2/list?offset=" + offset + "&limit=" + LIMIT
                    + (마지막수집시각.isEmpty() ? "" : "&since=" + 마지막수집시각);

            Map<String, Object> res = restTemplate.getForObject(url, Map.class);
            List<Map<String, Object>> page =
                    (List<Map<String, Object>>) (res == null ? List.of() : res.getOrDefault("문서", List.of()));

            호출로그.add("offset=" + offset + " → " + page.size() + "건");

            // 받아온 게 요청한 개수보다 적으면 마지막 페이지다.
            // ★ 사용자가 고친 것 : 담고 나서 나간다
            if (page.size() == LIMIT) {
                모은것.addAll(page);
                offset += LIMIT;
            }
            else if (page.size() < LIMIT) {
                모은것.addAll(page);
                offset += LIMIT;
                break;
            }

            잠깐(1200);   // 상대 서버 부하를 고려한 간격
        }

        int 중복 = 0;
        for (Map<String, Object> d : 모은것) {
            // ★ 커서를 앞당긴 대가로 같은 문서가 두 번 올 수 있다.
            //   doc_id 로 거른다. 실습 6 의 멱등성과 같은 장치다.
            if (이미있나(String.valueOf(d.get("doc_id")))) { 중복++; continue; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("doc_id", d.get("doc_id"));
            row.put("title", d.get("title"));
            row.put("dept", d.get("dept"));
            row.put("created_at", d.get("created_at"));
            색인.add(row);
        }

        LocalDateTime 종료 = LocalDateTime.now().withNano(0);
        지난수집종료 = 종료.format(FMT);

        // ★ 다음 수집의 기준은 '끝난 시각' 이 아니라 '시작한 시각' 이다.
        //   수집이 도는 동안 올라온 문서를 놓치지 않기 위해서다.
        마지막수집시각 = 시작.format(FMT);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("호출로그", 호출로그);
        out.put("이번에받아온건수", 모은것.size());
        out.put("그중중복", 중복);
        out.put("새로색인한건수", 모은것.size() - 중복);
        out.put("색인누적건수", 색인.size());
        out.put("수집시작", 지난수집시작);
        out.put("수집종료", 지난수집종료);
        out.put("저장한커서", 마지막수집시각);
        out.put("확인방법", "/interop/collect/truth 로 상대 기관의 실제 건수와 비교할 것");
        return out;
    }

    // ------------------------------------------------------------
    //  정답지 — 상대 기관에 실제 몇 건인지 물어본다
    // ------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> truth(String since) {
        String q = (since == null || since.isEmpty()) ? "" : "?since=" + since;
        Map<String, Object> r = restTemplate.getForObject(baseUrl + "/openapi/docs2/count" + q, Map.class);

        Map<String, Object> out = new LinkedHashMap<>(r == null ? Map.of() : r);
        out.put("우리색인건수", 색인.size());
        Object 실제 = out.get("실제건수");
        if (실제 instanceof Number n) {
            out.put("차이", n.intValue() - 색인.size());
        }
        return out;
    }

    // ------------------------------------------------------------
    //  수집이 도는 '도중' 에 문서가 하나 올라온 상황을 만든다
    // ------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> injectDuring() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (지난수집시작.isEmpty()) {
            out.put("결과", "먼저 /interop/collect/run 을 한 번 돌릴 것");
            return out;
        }

        // 지난 수집의 시작~종료 사이 한가운데 시각으로 문서를 만든다.
        LocalDateTime s = LocalDateTime.parse(지난수집시작, FMT);
        LocalDateTime e = LocalDateTime.parse(지난수집종료, FMT);
        LocalDateTime 가운데 = s.plusSeconds(Math.max(1, java.time.Duration.between(s, e).getSeconds() / 2));

        String at = 가운데.format(FMT);
        Map<String, Object> r = restTemplate.postForObject(
                baseUrl + "/openapi/docs2/inject?title=긴급공문.hwp&dept=기획부&created_at=" + at,
                null, Map.class);

        out.put("결과", "수집이 돌고 있던 시각에 문서가 한 건 올라왔다");
        out.put("그문서생성시각", at);
        out.put("지난수집", 지난수집시작 + " ~ " + 지난수집종료);
        out.put("지금커서", 마지막수집시각);
        out.put("상대응답", r);
        out.put("다음할일", "/interop/collect/run 을 한 번 더 돌려볼 것");
        return out;
    }

    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("색인건수", 색인.size());
        out.put("커서", 마지막수집시각.isEmpty() ? "(없음 - 전체 수집)" : 마지막수집시각);
        out.put("지난수집", 지난수집시작.isEmpty() ? "-" : 지난수집시작 + " ~ " + 지난수집종료);
        out.put("색인", 색인);
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> reset() {
        Map<String, Object> r = restTemplate.postForObject(baseUrl + "/openapi/docs2/reset", null, Map.class);
        색인.clear();
        마지막수집시각 = "";
        지난수집시작 = "";
        지난수집종료 = "";
        Map<String, Object> out = new LinkedHashMap<>(r == null ? Map.of() : r);
        out.put("우리색인", "비웠다");
        out.put("커서", "비웠다");
        return out;
    }

    /** 이미 색인에 있는 문서인가. 실무에서는 DB 의 unique 제약이나 upsert 로 한다. */
    private boolean 이미있나(String docId) {
        for (Map<String, Object> r : 색인) {
            if (docId.equals(String.valueOf(r.get("doc_id")))) return true;
        }
        return false;
    }

    private void 잠깐(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
