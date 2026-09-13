package com.study.interop;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

// ============================================================
//  시큐어코딩 (2) : 경로 조작 (Path Traversal).   (Phase 4.5 실습 1)
//
//  진단 6종 중 '입력값 검증' 항목. SQL 인젝션과 형제다.
//
//  ★ 전자정부 파일 다운로드에서 실제로 제일 많이 나오는 지적사항이다.
//    사용자가 준 '파일 이름' 을 그대로 경로에 붙이면, 사용자가
//    ../ 를 넣어 우리가 열어줄 생각이 없던 파일까지 읽어간다.
//
//  회사 소스에서 이렇게 생겼다 :
//    new File(기준폴더 + request.getParameter("fileName"))
//    Paths.get(baseDir, fileName)   <- 검증 없이 붙이면 취약
// ============================================================
@Service
public class FileDownloadClient {

    // 우리가 '내려줄 생각인' 폴더. 딱 이 안의 파일만 줘야 한다.
    private static final String BASE_DIR = "/app/download";

    /** 실습용 공개 파일을 하나 만들어 둔다(정상 케이스가 있어야 비교가 된다). */
    private void ensureSample() throws IOException {
        Path base = Paths.get(BASE_DIR);
        Files.createDirectories(base);
        Path sample = base.resolve("공지사항.txt");
        if (!Files.exists(sample)) {
            Files.writeString(sample, "이건 누구나 받아도 되는 공개 파일입니다.\n");
        }
    }

    // ------------------------------------------------------------
    //  ★ 취약. 파일 이름을 검증 없이 기준폴더에 붙인다.
    //
    //  값의 흐름 :
    //    fileName = "공지사항.txt"        -> /app/download/공지사항.txt        (의도한 것)
    //    fileName = "../certs/keystore.p12"
    //          -> /app/download/../certs/keystore.p12
    //          -> 정규화하면 /app/certs/keystore.p12   ★ 폴더를 탈출했다
    //          -> 개인키가 든 keystore 를 통째로 내려준다
    // ------------------------------------------------------------
    public Map<String, Object> downloadUnsafe(String fileName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("요청한이름", fileName);
        out.put("방식", "★ 취약 - 이름을 그대로 붙임");
        try {
            ensureSample();
            // ★ 검증이 하나도 없다. 이 한 줄이 지적사항이다.
            Path target = Paths.get(BASE_DIR, fileName);
            out.put("실제로연파일", target.normalize().toString());

            byte[] bytes = Files.readAllBytes(target);
            out.put("읽은크기", bytes.length);
            out.put("맛보기", 맛보기(target, bytes));
            out.put("결과", "성공 - 파일을 내려줬다");
        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", String.valueOf(e.getMessage()));
        }
        return out;
    }

    // ------------------------------------------------------------
    //  안전. 정규화한 뒤 '기준폴더 안인지' 를 확인한다.
    //
    //  ★ 핵심은 normalize() 가 아니라 startsWith() 다.
    //    ../ 를 지우는 게 아니라, 지운 결과가 우리 폴더 안에 있는지 '확인' 한다.
    //    (blacklist 로 "../" 를 걸러내는 방식은 우회가 많아 권장하지 않는다.
    //     %2e%2e, ....//, 절대경로 등. 그래서 whitelist = '안에 있는가' 로 판단한다)
    // ------------------------------------------------------------
    public Map<String, Object> downloadSafe(String fileName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("요청한이름", fileName);
        out.put("방식", "안전 - 정규화 후 기준폴더 안인지 확인");
        try {
            ensureSample();
            Path base = Paths.get(BASE_DIR).toAbsolutePath().normalize();
            Path target = base.resolve(fileName).normalize();     // ../ 가 여기서 계산된다
            out.put("정규화결과", target.toString());

            // ★ 방어선. 계산된 최종 경로가 기준폴더 밖이면 거부.
            if (!target.startsWith(base)) {
                out.put("결과", "★ 차단 - 기준폴더를 벗어난 접근이다");
                return out;
            }
            byte[] bytes = Files.readAllBytes(target);
            out.put("읽은크기", bytes.length);
            out.put("맛보기", 맛보기(target, bytes));
            out.put("결과", "성공");
        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** 텍스트면 앞부분을, 바이너리(keystore 등)면 그 사실만 알려준다. */
    private String 맛보기(Path p, byte[] bytes) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".p12") || name.endsWith(".jks") || name.endsWith(".key")) {
            return "★★ 개인키가 든 파일이다. 이게 유출되면 그 인증서는 그 순간 무효다.";
        }
        int n = Math.min(bytes.length, 60);
        return new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8);
    }
}
