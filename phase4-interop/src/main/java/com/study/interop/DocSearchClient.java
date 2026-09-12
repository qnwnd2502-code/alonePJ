package com.study.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// ============================================================
//  권한 승계 (Document-level Security).  (실습 8)
//
//  기관에 검색 시스템(RAG 포함)을 설치하면 반드시 만나는 문제다.
//
//    원본 파일서버        검색 시스템
//    ───────────         ──────────
//    인사팀 폴더    ───▶  전부 색인
//    복지팀 폴더    ───▶  전부 색인
//    감사팀 폴더    ───▶  전부 색인
//                          │
//                          └─▶ 검색창에서는 누구나 다 나온다   ★ 사고
//
//  ★ 원본에 걸려 있던 권한을 검색 결과까지 '이어받게' 만드는 것,
//    그게 권한 승계다. 승계에 실패하면 검색 시스템이 유출 통로가 된다.
//
//  방식은 두 가지뿐이고, 업계 용어가 있다.
//    early binding : 색인할 때 문서마다 '볼 수 있는 그룹' 을 같이 저장해두고
//                    검색할 때 그 사본으로 거른다.  빠르다. 대신 낡을 수 있다.
//    late binding  : 검색할 때마다 원본 시스템에 "얘 이거 봐도 돼?" 를 묻는다.
//                    항상 정확하다. 대신 느리고 원본을 두들긴다.
//
//  회사 소스에서 이런 이름으로 있다 :
//    AclFilter, SecurityTrimming, DocumentAcl, PermissionResolver,
//    Elasticsearch 라면 색인 필드에 acl_allow / acl_deny 로 박혀 있다.
// ============================================================
@Service
public class DocSearchClient {

    private static final Logger log = LoggerFactory.getLogger(DocSearchClient.class);

    @Value("${partner.base-url}")
    private String baseUrl;

    @Resource
    private RestTemplate restTemplate;

    // 우리 검색엔진의 '색인'. 실무에서는 Elasticsearch / 벡터DB 다.
    private final List<Map<String, Object>> 색인 = Collections.synchronizedList(new ArrayList<>());
    // 마지막으로 색인한 시각. '언제 적 권한인가' 를 말해주는 값이다.
    private volatile String 색인시각 = "아직 안 함";
    private volatile boolean 색인에권한있음 = false;

    // ------------------------------------------------------------
    //  0. 상대 시스템을 초기화하고, 사람이 누구인지 물어본다
    // ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> seed() {
        Map<String, Object> r = restTemplate.postForObject(baseUrl + "/openapi/docs/seed", null, Map.class);
        색인.clear();
        색인시각 = "아직 안 함";
        색인에권한있음 = false;
        Map<String, Object> out = new LinkedHashMap<>(r == null ? Map.of() : r);
        out.put("우리색인", "같이 비웠다");
        return out;
    }

    /**
     * 인사시스템에 "이 사람 누구냐" 를 묻는다.
     *
     * ★ 여기서 나오는 '실효그룹' 이 오늘의 절반이다.
     *   직접소속은 [인사팀] 하나인데 실효그룹은 [인사팀, 경영지원본부, 전직원] 이다.
     *   팀은 본부 아래에 있으므로 본부 권한도 물려받는다. 이걸 중첩 그룹이라 한다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> whoami(String user) {
        return restTemplate.getForObject(
                baseUrl + "/openapi/directory/user?id=" + user, Map.class);
    }

    // ------------------------------------------------------------
    //  1. 수집 · 색인
    // ------------------------------------------------------------

    /**
     * @param withAcl 원본에서 권한(allow/deny)까지 같이 받아올지.
     *
     * ★ withAcl=false 가 실제로 흔하다.
     *   수집 API 가 본문만 주고 권한은 안 주는 경우다. 그러면 우리는
     *   권한을 '알 수가 없어서' 거를 방법이 없다.
     *   ── 이건 코드로 못 고친다. 규격 협의로 고쳐야 한다.
     *      "권한 정보도 같이 주세요" 를 설계 단계에서 말했어야 하는 것이다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> index(boolean withAcl) {
        Map<String, Object> res = restTemplate.getForObject(
                baseUrl + "/openapi/docs/list?with_acl=" + withAcl, Map.class);

        List<Map<String, Object>> docs =
                (List<Map<String, Object>>) (res == null ? List.of() : res.getOrDefault("문서", List.of()));

        색인.clear();
        for (Map<String, Object> d : docs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.get("id"));
            row.put("제목", d.get("제목"));
            row.put("본문", d.get("본문"));
            // ★ 색인 시점의 권한을 '사본으로' 들고 있는다. 이게 early binding 이다.
            row.put("allow", d.get("allow"));
            row.put("deny", d.get("deny"));
            색인.add(row);
        }
        색인에권한있음 = withAcl;
        색인시각 = java.time.LocalTime.now().withNano(0).toString();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("색인건수", 색인.size());
        out.put("권한도같이색인", withAcl ? "예" : "★ 아니오 - 본문만 가져왔다");
        out.put("색인시각", 색인시각);
        out.put("주의", withAcl ? "-" : "권한이 없으니 어떤 필터도 걸 수 없다");
        return out;
    }

    public Map<String, Object> indexDump() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("색인시각", 색인시각);
        out.put("권한도같이색인", 색인에권한있음 ? "예" : "아니오");
        out.put("색인", new ArrayList<>(색인));
        return out;
    }

    // ------------------------------------------------------------
    //  2. 검색 — 오늘의 전부
    // ------------------------------------------------------------

    /**
     * @param mode  none       거르지 않는다 (사고 재현)
     *              allow-only 허용목록만 본다 (deny 를 잊은 흔한 버그)
     *              early      색인에 저장해둔 권한으로 거른다 (정석)
     *              late       원본에 한 건씩 물어본다 (항상 최신, 대신 비싸다)
     * @param nested 중첩 그룹을 펼칠지. off 면 직접 소속 팀만 본다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(String user, String q, String mode, boolean nested) {
        long start = System.currentTimeMillis();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("검색어", q);
        out.put("사용자", user);
        out.put("필터방식", 설명(mode));
        out.put("중첩그룹펼침", nested ? "예" : "★ 아니오 - 직접 소속 팀만 본다");

        // (1) 이 사람이 누구인지 인사시스템에 묻는다. 검색 1회당 1번이다.
        Map<String, Object> me = whoami(user);
        List<String> 직접 = (List<String>) me.get("직접소속");
        List<String> 실효 = (List<String>) me.get("실효그룹");
        Set<String> 내그룹 = new LinkedHashSet<>(nested ? 실효 : 직접);
        out.put("이름", me.get("이름"));
        out.put("적용된그룹", new ArrayList<>(내그룹));

        // (2) 본문 검색. 여기까지는 어떤 검색엔진이든 똑같다.
        List<Map<String, Object>> 후보 = new ArrayList<>();
        for (Map<String, Object> d : 색인) {
            String 제목 = String.valueOf(d.get("제목"));
            String 본문 = String.valueOf(d.get("본문"));
            if (q == null || q.isBlank() || 제목.contains(q) || 본문.contains(q)) {
                후보.add(d);
            }
        }
        out.put("본문으로걸린건수", 후보.size());

        // (3) ★ 권한으로 거른다. 여기가 '권한 승계' 다.
        List<Map<String, Object>> 결과 = new ArrayList<>();
        List<String> 차단로그 = new ArrayList<>();

        for (Map<String, Object> d : 후보) {
            String id = String.valueOf(d.get("id"));
            String 제목 = String.valueOf(d.get("제목"));
            boolean 허용;
            String 사유;

            switch (mode) {
                case "none" -> {
                    허용 = true;
                    사유 = "필터 없음";
                }
                case "allow-only" -> {
                    Set<String> allow = 집합(d.get("allow"));
                    허용 = 겹치나(내그룹, allow);
                    사유 = 허용 ? "allow 에 걸림" : "allow 에 없음";
                    // ★ deny 를 아예 안 본다. 이 한 줄이 빠진 게 오늘의 버그다.
                }
                case "late" -> {
                    // ★ 문서 '한 건마다' 원본에 물어본다. 10건이면 10번이다.
                    Map<String, Object> r = restTemplate.getForObject(
                            baseUrl + "/openapi/docs/can-read?doc=" + id + "&user=" + user, Map.class);
                    허용 = Boolean.TRUE.equals(r == null ? null : r.get("허용"));
                    사유 = String.valueOf(r == null ? "-" : r.get("사유")) + " (원본에 직접 물어봄)";
                }
                default -> {   // "early"
                    Set<String> allow = 집합(d.get("allow"));
                    Set<String> deny = 집합(d.get("deny"));
                    // ★ 순서가 중요하다. deny 를 '먼저' 본다. 거부가 허용을 이긴다.
                    if (겹치나(내그룹, deny)) {
                        허용 = false;
                        사유 = "deny 에 걸림 (거부가 허용을 이긴다)";
                    } else if (겹치나(내그룹, allow)) {
                        허용 = true;
                        사유 = "allow 에 걸림";
                    } else {
                        허용 = false;
                        사유 = "allow 에 없음";
                    }
                }
            }

            if (허용) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("제목", 제목);
                row.put("사유", 사유);
                결과.add(row);
            } else {
                차단로그.add(id + " " + 제목 + " -> " + 사유);
            }
        }

        out.put("보여준건수", 결과.size());
        out.put("검색결과", 결과);
        out.put("차단됨", 차단로그);
        out.put("걸린시간ms", System.currentTimeMillis() - start);
        return out;
    }

    private String 설명(String mode) {
        return switch (mode) {
            case "none" -> "★ 없음 - 권한을 아예 안 본다";
            case "allow-only" -> "★ 허용목록만 - deny 를 안 본다";
            case "late" -> "late binding - 검색할 때마다 원본에 물어본다";
            default -> "early binding - 색인에 저장해둔 권한으로 거른다";
        };
    }

    @SuppressWarnings("unchecked")
    private Set<String> 집합(Object o) {
        if (o instanceof List<?> l) return new LinkedHashSet<>((List<String>) l);
        return new LinkedHashSet<>();
    }

    private boolean 겹치나(Set<String> a, Set<String> b) {
        for (String s : a) if (b.contains(s)) return true;
        return false;
    }

    // ------------------------------------------------------------
    //  3. 권한이 바뀌는 순간 — early binding 의 약점
    // ------------------------------------------------------------

    /**
     * 관리자가 원본에서 권한을 회수한다.
     *
     * ★ 바뀌는 건 원본뿐이다. 우리 색인은 색인하던 그 순간의 권한을 들고 있다.
     *   재색인 전까지 early binding 은 '옛 권한' 으로 계속 보여준다.
     *   현실의 문장으로 옮기면 :
     *     "어제 부서 이동했는데 아직 옛 부서 문서가 검색돼요"
     *     "권한 회수했다는데 왜 아직 보이죠?"
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> revoke(String doc, String group) {
        Map<String, Object> r = restTemplate.postForObject(
                baseUrl + "/openapi/docs/revoke?doc=" + doc + "&group=" + group, null, Map.class);
        Map<String, Object> out = new LinkedHashMap<>(r == null ? Map.of() : r);
        out.put("우리색인시각", 색인시각);
        out.put("★", "지금 early 로 검색하면 아직 보인다. late 로 하면 즉시 막힌다.");
        return out;
    }

    /** late binding 이 원본을 몇 번 두들겼는지. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> aclCalls() {
        return restTemplate.getForObject(baseUrl + "/openapi/docs/acl-calls", Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> aclCallsReset() {
        return restTemplate.postForObject(baseUrl + "/openapi/docs/acl-calls/reset", null, Map.class);
    }
}
