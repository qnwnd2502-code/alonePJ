package com.study.interop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

// ============================================================
//  전문 위·변조 방지 서명(HMAC) 을 만드는 부품.
//
//  ★ 이름을 Signer 로 지었다. 회사 소스에서는
//    XxxSignUtil, XxxCryptoUtil, SignatureGenerator 같은 이름으로 있다.
//    (연계 규격서에 '서명값' 이 있으면 이런 클래스가 반드시 하나 있다)
//
//  ★ HMAC 은 암호화가 아니다. 되돌릴 수 없고, 되돌릴 필요도 없다.
//    받는 쪽은 '복호화' 하는 게 아니라 '같은 재료로 다시 계산해서 비교' 한다.
//    그래서 본문은 평문 그대로 보낸다. 숨기는 건 TLS 가 한다.
// ============================================================
@Component
public class HmacSigner {

    // 양쪽이 같은 값을 가져야 한다(대칭). API Key 와 성격이 같다.
    // 값이 오는 길: .env -> docker-compose.yml -> 컨테이너 환경변수
    //             -> application.properties -> 아래 @Value -> 이 필드
    @Value("${partner.hmac-secret}")
    private String secret;

    // ------------------------------------------------------------
    //  ★ canonical string — '서명할 대상' 을 조립한다.
    //
    //  규격서에 이 순서가 반드시 적혀 있고, 한 글자라도 다르면 서명이 안 맞는다.
    //  상대 기관 코드(app.py 의 build_canonical)와 순서가 같아야 한다.
    //
    //      timestamp \n method \n path \n body
    //
    //  ★ 실무에서 HMAC 이 안 맞을 때 원인은 거의 이 셋 중 하나다:
    //    1) 인코딩 (한쪽이 UTF-8, 한쪽이 cp949)  <- 한글 든 요청만 실패한다
    //    2) 조립 순서/구분자 (\n 인지 & 인지, path 에 쿼리를 넣는지)
    //    3) 비밀키가 다름 (개발계/운영계 키를 섞어 씀)
    // ------------------------------------------------------------
    public String canonical(String timestamp, String method, String path, String body) {
        return String.join("\n", timestamp, method, path, body);
    }

    // ------------------------------------------------------------
    //  HMAC-SHA256 을 계산해 16진수 문자열로 돌려준다.
    // ------------------------------------------------------------
    public String sign(String canonical) {
        try {
            // Mac = Message Authentication Code. 자바 표준 라이브러리다(외부 의존성 없음).
            Mac mac = Mac.getInstance("HmacSHA256");

            // 비밀키도 바이트로 바꿔서 넣는다. 여기도 인코딩을 명시한다.
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            // ★ 오늘 자바 10분의 그 줄.
            //   getBytes() 를 빈 괄호로 두면 자바 17 이하에서는 OS 기본 인코딩을 쓴다.
            //   -> 윈도우(cp949) 개발 PC 와 리눅스(UTF-8) 운영 서버의 서명이 달라진다.
            //   -> "개발에선 되는데 운영에서 401" 의 단골 원인. 반드시 명시한다.
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));

            return toHex(raw);

        } catch (Exception e) {
            // 알고리즘 이름 오타나 키가 null 인 경우다. 조용히 넘기면 안 된다.
            throw new IllegalStateException("서명 생성 실패", e);
        }
    }

    // ------------------------------------------------------------
    //  byte[] -> 16진수 문자열.
    //
    //  ★ 왜 이 변환이 필요한가:
    //    HMAC 결과는 32바이트짜리 '아무 값' 이다. 그 안에는 글자가 아닌 바이트도 있어서
    //    HTTP 헤더에 그대로 실을 수 없다. 그래서 사람이 읽고 옮길 수 있는
    //    형태로 바꿔서 보낸다. 방식이 두 가지다:
    //      - Hex(16진수)  : 32바이트 -> 64글자.  0-9 a-f 만 나온다. 우리가 쓰는 것
    //      - Base64       : 32바이트 -> 44글자.  더 짧지만 +/= 가 섞인다
    //    ★ 규격서에 어느 쪽인지 적혀 있다. 다르면 당연히 서명이 안 맞는다.
    // ------------------------------------------------------------
    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            // 0xff 로 마스킹하는 이유: 자바 byte 는 -128~127 이라 음수가 나온다.
            // & 0xff 를 해서 0~255 로 만든 뒤 16진수 두 자리로 찍는다.
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
