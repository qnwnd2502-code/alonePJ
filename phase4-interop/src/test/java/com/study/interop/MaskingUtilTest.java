package com.study.interop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 개인정보 마스킹 단위테스트.
 * 작성 : 박사원 / 2026-09-22
 */
class MaskingUtilTest {

    @Test
    @DisplayName("UT-001 이름 마스킹 - 3글자")
    void maskName_3글자() {
        assertEquals("홍*동", MaskingUtil.maskName("홍길동"));
    }

    @Test
    @DisplayName("UT-002 이름 마스킹 - 2글자")
    void maskName_2글자() {
        assertEquals("홍*", MaskingUtil.maskName("홍길"));
    }
    

    @Test
    @DisplayName("UT-003 주민번호 마스킹 - 하이픈 있음")
    void maskRrn_하이픈있음() {
        assertEquals("900101-1******", MaskingUtil.maskRrn("900101-1234567"));
    }

    @Test
    @DisplayName("UT-005 주민번호 마스킹 - 하이픈 없음")
    void maskRrn_하이픈없음() {
        assertEquals("9001011******", MaskingUtil.maskRrn("9001011234567"));
    }

    @Test
    @DisplayName("UT-004 휴대폰 마스킹")
    void maskPhone() {
        assertEquals("010-****-5678", MaskingUtil.maskPhone("010-1234-5678"));
    }

    // ---- 코드리뷰에서 추가 (2026-09-26) : 형식이 이상한 값은 '전부 가린다' (fail-closed) ----

    @Test
    @DisplayName("UT-006 주민번호 - 자릿수가 모자라면 전부 가린다")
    void maskRrn_형식오류() {
        assertEquals("************", MaskingUtil.maskRrn("900101-12345"));
    }

    @Test
    @DisplayName("UT-007 휴대폰 - 짧은 값도 예외 없이 전부 가린다")
    void maskPhone_짧은값() {
        assertEquals("*****", MaskingUtil.maskPhone("12345"));
    }
}
