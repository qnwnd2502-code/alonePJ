package com.study.interop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

// ============================================================
//  연계 3형태 중 ③ DB 직접.
//  상대 기관 DB 에 우리가 직접 SELECT 를 쏜다.
//
//  ★ 잘 된다. 빠르다. 그래서 위험하다. ★
//    HTTP 를 안 거치니 API 보다 빠르고, 규격서 협의도 필요 없다.
//    그런데 우리가 상대의 '테이블 구조' 에 직접 묶인다.
//    상대 DBA 가 컬럼 이름을 바꾸면 우리는 아무 예고 없이 깨진다.
//    그리고 그건 상대 잘못이 아니다 - 우리가 몰래 들여다보고 있었던 것이다.
// ============================================================
@Service
public class PartnerDbClient {

    @Resource
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------
    //  방식 A) 상대 '테이블' 을 직접 SELECT 한다.
    //  ★ 컬럼 이름이 우리 소스에 그대로 박힌다. 이게 결합이다.
    // ------------------------------------------------------------
    public List<Map<String, Object>> selectDirect() {
        String sql = """
                SELECT atch_file_id
                     , orignl_file_nm
                     , file_stre_cours
                     , file_mg
                  FROM comtnfiledetail
                 WHERE use_yn = 'Y'
                 ORDER BY atch_file_id
                """;
        // queryForList = 결과를 List<Map> 으로 받는다. Phase 3 의 resultType="map" 과 같은 모양
        return jdbcTemplate.queryForList(sql);
    }

    // ------------------------------------------------------------
    //  방식 B) 상대가 우리 전용으로 만들어준 '뷰(View)' 만 본다.
    //  ★ 이게 실무에서 말하는 'DB 직접 연계' 의 실제 모습이다.
    //    뷰가 곧 계약서다. 상대는 안쪽 테이블을 마음대로 바꿔도
    //    뷰만 유지하면 우리는 안 깨진다.
    // ------------------------------------------------------------
    public List<Map<String, Object>> selectByView() {
        String sql = "SELECT file_id, file_name, file_size FROM v_file_for_partner ORDER BY file_id";
        return jdbcTemplate.queryForList(sql);
    }
}
