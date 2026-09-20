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

        int 신규 = 0, 갱신 = 0;
        for (Map<String, Object> d : 모은것) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("doc_id", d.get("doc_id"));
            row.put("title", d.get("title"));
            row.put("dept", d.get("dept"));
            row.put("created_at", d.get("created_at"));
            row.put("allow", d.get("allow"));

            // ★ upsert : 이미 있으면 통째로 덮어쓰고, 없으면 새로 넣는다.
            //   "무엇이 바뀌었는지" 를 비교하지 않는다. 상대가 보내줬다는 건
            //   뭔가 바뀌었다는 뜻이고, 필드가 늘어나도 이 코드는 그대로다.
            //   같은 걸 몇 번 해도 결과가 같다 = 멱등 연산이다.
            int 자리 = 찾기(String.valueOf(d.get("doc_id")));
            if (자리 >= 0) { 색인.set(자리, row); 갱신++; }
            else           { 색인.add(row);      신규++; }
        }

        LocalDateTime 종료 = LocalDateTime.now().withNano(0);
        지난수집종료 = 종료.format(FMT);

        // ★ 다음 수집의 기준은 '끝난 시각' 이 아니라 '시작한 시각' 이다.
        //   수집이 도는 동안 올라온 문서를 놓치지 않기 위해서다.
        마지막수집시각 = 시작.format(FMT);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("호출로그", 호출로그);
        out.put("이번에받아온건수", 모은것.size());
        out.put("신규", 신규);
        out.put("갱신", 갱신);
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

    // ------------------------------------------------------------
    //  전체 대조 (reconciliation)
    //
    //  ★ 증분 수집은 삭제를 원리상 못 잡는다.
    //    변경은 이벤트로 오지만 삭제는 '부재' 이고, 부재는 전송되지 않는다.
    //
    //  그래서 주기적으로 '지금 있는 것 전부' 를 받아 우리 색인과 맞춰본다.
    //  본문은 안 받고 id 목록만 받으므로 전체 재수집보다 훨씬 싸다.
    // ------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> reconcile(boolean apply) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> r = restTemplate.getForObject(baseUrl + "/openapi/docs2/ids", Map.class);
        List<String> 상대ids = (List<String>) (r == null ? List.of() : r.getOrDefault("ids", List.of()));

        List<String> 우리에만있다 = new ArrayList<>();
        for (Map<String, Object> row : 색인) {
            String id = String.valueOf(row.get("doc_id"));
            if (!상대ids.contains(id)) 우리에만있다.add(id);
        }

        out.put("상대건수", 상대ids.size());
        out.put("우리색인건수", 색인.size());
        out.put("우리에만있는것", 우리에만있다);
        out.put("판정", 우리에만있다.isEmpty() ? "일치" : "★ " + 우리에만있다.size() + "건이 상대에서 사라졌다");

        if (apply && !우리에만있다.isEmpty()) {
            색인.removeIf(row -> 우리에만있다.contains(String.valueOf(row.get("doc_id"))));
            out.put("조치", "색인에서 제거했다");
            out.put("제거후색인건수", 색인.size());
        } else if (!우리에만있다.isEmpty()) {
            out.put("조치", "없음 (apply=on 을 붙이면 실제로 제거한다)");
        }

        // ★ 실무에서는 바로 지우지 않는다.
        //   1일차 '미확인' -> 3일차 비활성화(검색 제외) -> 30일차 물리 삭제.
        //   상대가 "폴더 옮긴 거였어요" 라고 하는 날이 반드시 오기 때문이다.
        //   그리고 목록 조회가 실패했거나 건수가 급감했으면 판정 자체를 건너뛴다.
        out.put("실무주의", "즉시 삭제하지 말 것. 비활성화 -> 유예 -> 삭제. 건수 급감 시 판정 중단");
        return out;
    }

    // ------------------------------------------------------------
    //  검색 — 색인에 저장해둔 권한으로 거른다 (실습 8 의 early binding)
    // ------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(String user) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> u = restTemplate.getForObject(
                baseUrl + "/openapi/docs2/user?id=" + user, Map.class);
        String 부서 = String.valueOf(u == null ? "" : u.get("부서"));

        out.put("사용자", user);
        out.put("이름", u == null ? "-" : u.get("이름"));
        out.put("부서", 부서);

        List<Map<String, Object>> 보여줄것 = new ArrayList<>();
        for (Map<String, Object> r : 색인) {
            Object allow = r.get("allow");
            boolean 허용 = (allow instanceof List<?> l) && l.contains(부서);
            if (허용) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("doc_id", r.get("doc_id"));
                row.put("title", r.get("title"));
                row.put("색인에저장된allow", allow);
                보여줄것.add(row);
            }
        }
        out.put("검색결과건수", 보여줄것.size());
        out.put("검색결과", 보여줄것);
        return out;
    }

    // ------------------------------------------------------------
    //  상대 기관에서 일어나는 일 (학습용 창구)
    // ------------------------------------------------------------
    @SuppressWarnings("unchecked")
    public Map<String, Object> revoke(String docId) {
        return restTemplate.postForObject(
                baseUrl + "/openapi/docs2/revoke?doc_id=" + docId, null, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> remove(String docId) {
        return restTemplate.postForObject(
                baseUrl + "/openapi/docs2/remove?doc_id=" + docId, null, Map.class);
    }

    /** 색인에서 그 문서의 자리를 찾는다. 없으면 -1.
     *  실무에서는 DB 의 unique 제약 + upsert(MERGE) 한 방으로 끝낸다. */
    private int 찾기(String docId) {
        for (int i = 0; i < 색인.size(); i++) {
            if (docId.equals(String.valueOf(색인.get(i).get("doc_id")))) return i;
        }
        return -1;
    }

    private void 잠깐(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
