package com.study.interop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ============================================================
//  시큐어코딩 (1) : SQL 인젝션.   (Phase 4.5 실습 1)
//
//  진단 6종 중 '입력값 검증' 항목. 보안약점 진단 도구가 제일 먼저,
//  그리고 제일 많이 잡아내는 지적사항이다.
//
//  ★ Phase 3 에서 예고한 것의 회수다.
//    MyBatis 에서 #{name} 은 안전하고 ${name} 은 위험하다고 했다.
//    그 '위험' 이 정확히 무엇인지 오늘 눈으로 본다.
//
//  회사 소스에서 이렇게 생겼다 :
//    "SELECT ... WHERE name = '" + name + "'"          <- 자바 문자열 이어붙이기
//    "... WHERE name = #{name}"  (안전)  vs  "${name}" (위험)   <- MyBatis
// ============================================================
@Service
public class InsecureDbClient {

    @Resource
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------
    //  ★ 취약. 입력값을 SQL 문자열에 그대로 이어붙인다.
    //
    //  값의 흐름 :
    //    사용자 입력 kw
    //      -> "...LIKE '%" + kw + "%'"  로 문자열이 '완성' 된다
    //      -> DB 는 완성된 문장을 받는다. 어디까지가 '데이터' 이고
    //         어디부터가 '명령' 인지 구분할 방법이 없다
    //      -> kw 안에 넣은 따옴표가 문장 구조를 바꿔버린다
    // ------------------------------------------------------------
    public Map<String, Object> searchConcat(String kw) {
        // 원래 의도 : use_yn='Y' 인 것 중 이름에 kw 가 들어간 파일만
        String sql = "SELECT atch_file_id, orignl_file_nm, use_yn"
                   + "  FROM comtnfiledetail"
                   + " WHERE use_yn = 'Y'"
                   + "   AND orignl_file_nm LIKE '%" + kw + "%'"
                   + " ORDER BY atch_file_id";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("입력값", kw);
        out.put("방식", "★ 취약 - 문자열 이어붙이기");
        out.put("DB가받은문장", sql);          // ★ 이 줄이 오늘의 핵심. 완성된 문장을 그대로 보여준다.
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            out.put("나온건수", rows.size());
            out.put("결과", rows);
        } catch (Exception e) {
            out.put("결과", "SQL 오류");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------
    //  안전. 입력값을 '자리(?)' 로만 넘긴다. (PreparedStatement 바인딩)
    //
    //  값의 흐름 :
    //    문장 구조를 '먼저' DB 에 보낸다 : "...LIKE '%' || ? || '%'"
    //      -> DB 가 구조를 이미 확정한다
    //      -> 그 다음에 kw 를 '데이터로만' 채워 넣는다
    //      -> kw 안에 따옴표가 있어도 그냥 '따옴표라는 글자' 일 뿐, 구조를 못 바꾼다
    // ------------------------------------------------------------
    public Map<String, Object> searchBind(String kw) {
        String sql = "SELECT atch_file_id, orignl_file_nm, use_yn"
                   + "  FROM comtnfiledetail"
                   + " WHERE use_yn = 'Y'"
                   + "   AND orignl_file_nm LIKE '%' || ? || '%'"    // ★ 값 자리는 ? 하나뿐
                   + " ORDER BY atch_file_id";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("입력값", kw);
        out.put("방식", "안전 - 파라미터 바인딩(?)");
        out.put("DB가받은문장", sql);          // ? 가 그대로 남는다. kw 는 여기 안 섞인다.
        out.put("따로넘긴값", kw);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, kw);
            out.put("나온건수", rows.size());
            out.put("결과", rows);
        } catch (Exception e) {
            out.put("결과", "SQL 오류");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", e.getMessage());
        }
        return out;
    }
}
