package com.study.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ============================================================
//  시큐어코딩 (3) : 오류 처리.   (Phase 4.5 실습 2)
//
//  진단 6종 중 '오류 처리' 항목. 세 가지가 한 덩어리로 나온다.
//    ① 오류 메시지를 통한 정보 노출   - 스택트레이스·SQL 원문을 사용자에게 보여준다
//    ② 오류 상황 대응 부재            - 빈 catch. 예외를 삼킨다
//    ③ 부적절한 예외 처리             - catch (Exception e) 로 다 뭉뚱그린다
//
//  ★ 이 항목이 무서운 이유는 '단독으로는 피해가 없어 보인다' 는 것이다.
//    화면이 좀 지저분한 것뿐 아닌가? 아니다.
//    오류 메시지는 공격자의 '지도' 이고, 삼킨 예외는 '조용한 데이터 사고' 다.
// ============================================================
@Service
public class ErrorHandlingClient {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlingClient.class);

    @Resource
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------
    //  (1) 정보 노출
    // ------------------------------------------------------------

    /** 일부러 진짜 DB 오류를 낸다. (없는 컬럼을 조회) */
    private void 진짜오류내기() {
        jdbcTemplate.queryForList(
                "SELECT secret_column FROM comtnfiledetail WHERE use_yn = 'Y'");
    }

    /**
     * ★ 취약. 예외 메시지와 스택트레이스를 응답에 그대로 담는다.
     *
     * 회사 소스에서 이렇게 생겼다 :
     *   catch (Exception e) { model.addAttribute("msg", e.getMessage()); }
     *   catch (Exception e) { e.printStackTrace(); }
     *   response.getWriter().print(e.toString());
     *
     * ★ 공격자가 여기서 얻는 것 :
     *   - 테이블명, 컬럼명       -> 다음 SQL 인젝션의 재료 (실습 1 과 이어진다)
     *   - 패키지 구조, 클래스명   -> 어떤 프레임워크를 쓰는지
     *   - 라이브러리 버전        -> 알려진 취약점(CVE)을 찾아본다
     *   - 파일 경로             -> 경로 조작의 출발점 (실습 1 과 이어진다)
     */
    public Map<String, Object> leak() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("방식", "★ 취약 - 예외 내용을 사용자에게 그대로 보여준다");
        try {
            진짜오류내기();
            out.put("결과", "성공");
        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외클래스", e.getClass().getName());
            out.put("메시지", e.getMessage());          // ★ SQL 원문과 테이블 구조가 여기 들어있다
            out.put("스택트레이스", 스택을글자로(e));      // ★ 패키지 구조와 라이브러리 버전이 여기
        }
        return out;
    }

    /**
     * 안전. 사용자에게는 '추적번호' 만 주고, 자세한 내용은 서버 로그에만 남긴다.
     *
     * ★ 핵심은 '숨기는 것' 이 아니라 '나눠 담는 것' 이다.
     *   사용자 화면 : 무슨 일이 났는지 + 추적번호
     *   서버 로그   : 전부 (스택트레이스 포함)
     *
     *   추적번호가 없으면 "오류가 발생했습니다" 만 남아서 아무도 못 찾는다.
     *   민원인이 전화로 ERR-a1b2c3d4 를 불러주면 로그에서 그 한 건을 바로 찾는다.
     *   실무 용어로 correlation id / trace id 라고 부른다.
     */
    public Map<String, Object> safe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("방식", "안전 - 추적번호만 주고 자세한 건 로그에");
        try {
            진짜오류내기();
            out.put("결과", "성공");
        } catch (Exception e) {
            String 추적번호 = "ERR-" + UUID.randomUUID().toString().substring(0, 8);
            // ★ 로그에는 전부 남긴다. 예외 객체를 '두 번째 인자' 로 넘기면 스택까지 찍힌다.
            //   log.error("...", e.getMessage())  <- 이러면 스택이 안 남는다. 흔한 실수.
            log.error("[{}] 파일 목록 조회 실패", 추적번호, e);

            out.put("결과", "실패");
            out.put("안내", "처리 중 오류가 발생했습니다. 담당자에게 아래 번호를 알려주세요.");
            out.put("추적번호", 추적번호);
            out.put("확인방법", "docker compose logs boot | grep " + 추적번호);
        }
        return out;
    }

    // ------------------------------------------------------------
    //  (2) 예외를 삼킨다 (빈 catch)
    // ------------------------------------------------------------

    /**
     * 배치가 3건을 처리하는데 2번째가 실패한다.
     *
     * @param swallow true 면 빈 catch 로 삼킨다.
     *
     * ★ 실습 7 에서 예고한 '조용한 사고' 가 이것이다.
     *   삼키면 배치는 "정상 종료" 로 끝난다. 로그도 깨끗하다.
     *   그런데 데이터는 2건만 들어가 있다. 아무도 모른다.
     *   다음 달 정산에서 한 명이 수당을 못 받고 민원이 들어와야 안다.
     */
    public Map<String, Object> batch(boolean swallow) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("방식", swallow ? "★ 취약 - 빈 catch 로 삼킨다" : "안전 - 실패를 세고 보고한다");

        List<String> 처리로그 = new ArrayList<>();
        int 성공 = 0, 실패 = 0;

        for (int i = 1; i <= 3; i++) {
            try {
                if (i == 2) {
                    throw new IllegalStateException("2번 건의 기관코드가 규격과 다릅니다");
                }
                처리로그.add(i + "번 : 적재 완료");
                성공++;
            } catch (Exception e) {
                if (swallow) {
                    // ★ 여기가 지적사항이다. 아무것도 안 한다.
                    //   진단 도구는 'catch 블록이 비어 있음' 으로 잡아낸다.
                    처리로그.add(i + "번 : (조용히 넘어감)");
                } else {
                    // 최소한 이 세 가지는 해야 한다 : 로그 남기기 / 세기 / 결과에 알리기
                    실패++;
                    log.warn("배치 {}번 건 처리 실패", i, e);
                    처리로그.add(i + "번 : ★ 실패 - " + e.getMessage());
                }
            }
        }

        out.put("처리로그", 처리로그);
        out.put("성공", 성공);
        out.put("실패", swallow ? "0 (센 적이 없다)" : String.valueOf(실패));
        out.put("배치결과보고", swallow
                ? "정상 종료 ★ 거짓말이다. 3건 중 2건만 들어갔다"
                : (실패 > 0 ? "★ 실패 " + 실패 + "건 - 담당자 확인 필요" : "정상 종료"));
        return out;
    }

    // ------------------------------------------------------------
    //  (3) 로그에 개인정보를 찍는다
    // ------------------------------------------------------------

    /**
     * @param mask true 면 마스킹한다.
     *
     * ★ 로그는 '안전한 곳' 이 아니다.
     *   - 운영 로그는 보통 여러 사람이 본다 (개발자, 운영자, 협력업체)
     *   - 로그는 파일로 쌓이고, 백업되고, 로그 수집 서버로 전송된다
     *   - 개인정보가 로그에 있으면 그 로그 파일 전체가 개인정보 파일이 된다
     *
     *   진단 항목이면서 동시에 개인정보보호법 이슈다. 지적사항 중 반박이 어려운 쪽.
     */
    public Map<String, Object> pii(boolean mask) {
        String 이름 = "홍길동";
        String 주민번호 = "900101-1234567";
        String 비밀번호 = "P@ssw0rd!2026";
        String 토큰 = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJob25nIn0.abcdefg";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("방식", mask ? "안전 - 마스킹" : "★ 취약 - 그대로 찍는다");

        if (mask) {
            log.info("로그인 시도 이름={} 주민={} 비밀번호={} 토큰={}",
                    가리기이름(이름), 가리기주민(주민번호), "****", 가리기토큰(토큰));
            out.put("로그에찍힌것", "이름=홍*동 주민=900101-1****** 비밀번호=**** 토큰=eyJhbGciOi...(뒤 생략)");
        } else {
            // ★ 이 한 줄이 지적사항이다. 파라미터를 통째로 찍는 습관에서 나온다.
            log.info("로그인 시도 이름={} 주민={} 비밀번호={} 토큰={}",
                    이름, 주민번호, 비밀번호, 토큰);
            out.put("로그에찍힌것", "이름=홍길동 주민=900101-1234567 비밀번호=P@ssw0rd!2026 토큰=eyJ...");
        }
        out.put("확인방법", "docker compose logs boot --tail 5");
        out.put("★", "실무에서 더 흔한 형태는 이것이다 : log.info(\"파라미터={}\", paramMap)");
        return out;
    }

    private String 가리기이름(String s) {
        if (s == null || s.length() < 2) return "*";
        if (s.length() == 2) return s.charAt(0) + "*";
        return s.charAt(0) + "*".repeat(s.length() - 2) + s.charAt(s.length() - 1);
    }

    private String 가리기주민(String s) {
        // 생년월일과 성별 한 자리까지만 남긴다. 뒤 6자리가 핵심 개인정보다.
        if (s == null || s.length() < 8) return "******";
        return s.substring(0, 8) + "******";
    }

    private String 가리기토큰(String s) {
        if (s == null || s.length() <= 10) return "****";
        return s.substring(0, 10) + "...(뒤 생략)";
    }

    private String 스택을글자로(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 1500 ? s.substring(0, 1500) + " ...(생략)" : s;
    }
}
