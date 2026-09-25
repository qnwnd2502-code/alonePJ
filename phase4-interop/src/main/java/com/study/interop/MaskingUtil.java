package com.study.interop;

/**
 * 개인정보 마스킹 유틸.
 * 화면 표시와 로그 출력 전에 반드시 이걸 거친다. (요구사항 SFR-PII-01 ~ 03)
 *
 * 작성 : 박사원 / 2026-09-22
 */
public final class MaskingUtil {

    private MaskingUtil() {
    }

    /** 이름 : 첫 글자와 끝 글자만 남긴다.  홍길동 -> 홍*동 */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.length() == 1) return name;
        if (name.length() == 2) return name.charAt(0) + "*";
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    /** 주민번호 : 뒷자리 첫 숫자만 남기고 가린다.  900101-1234567 -> 900101-1****** */
    public static String maskRrn(String rrn) {
        if (rrn == null) return null;

        if (rrn.length() == 14 && rrn.charAt(6) == '-') {      // 900101-1234567
            return rrn.substring(0, 8) + "******";
        } else if (rrn.length() == 13) {                       // 9001011234567
            return rrn.substring(0, 7) + "******";
        }

        // 형식이 이상하면 원본을 돌려주지 않고 전부 가린다 (fail-closed)
        return "*".repeat(rrn.length());
    }

    /** 휴대폰 : 가운데 번호를 가린다.  010-1234-5678 -> 010-****-5678 */
    public static String maskPhone(String phone) {
        if (phone == null) return null;

        if (phone.contains("-")){
            String[] parts = phone.split("-");
            if (parts.length == 3) {
                return parts[0] + "-" + "*".repeat(parts[1].length()) + "-" + parts[2];
            }
        }

        if (phone.length() == 11) {                            // 01012345678
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }

        // 형식이 이상하면 원본을 돌려주지 않고 전부 가린다 (fail-closed)
        return "*".repeat(phone.length());
    }
}