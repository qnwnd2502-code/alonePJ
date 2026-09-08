package com.study.interop;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

// ============================================================
//  파일 배치(SFTP) 연계.  (실습 7)
//
//  연계 3형태 중 '파일' 이다.
//    1) API   - 실습 1~6 에서 한 것
//    2) 파일   - 오늘. 공공 연계의 절반 이상이 아직 여기 있다
//    3) DB직접 - 실습 2 에서 한 것
//
//  왜 아직도 파일인가 :
//    - 망분리. 업무망과 인터넷망이 끊겨 있어 실시간 호출 자체가 불가능한 곳이 많다
//    - 대량. 수십만 건을 API 로 한 건씩 부르면 밤이 새도 안 끝난다
//    - 오래됨. 20년 전에 만든 연계는 그때 방식 그대로 돌고 있다
//
//  회사 소스에서 이런 이름으로 있다 :
//    XxxBatchJob, FileTransferUtil, SftpUtil, XxxScheduler, ...
//    스프링 배치(Spring Batch) 나 quartz 스케줄러가 이 클래스를 밤에 깨운다.
// ============================================================
@Service
public class SftpBatchClient {

    private static final Logger log = LoggerFactory.getLogger(SftpBatchClient.class);

    @Value("${sftp.host}")       private String host;
    @Value("${sftp.port}")       private int    port;
    @Value("${sftp.user}")       private String user;
    @Value("${sftp.password}")   private String password;
    @Value("${sftp.remote-dir}") private String remoteDir;
    @Value("${sftp.local-dir}")  private String localDir;

    @Value("${partner.base-url}") private String baseUrl;

    @Resource
    private RestTemplate restTemplate;

    // ------------------------------------------------------------
    //  전문 레이아웃 (연계규격서가 정한 것. 우리가 정하는 게 아니다)
    //
    //    위치   길이   항목        비고
    //     1      8    기관코드
    //     9     20    성명        ★ 한글. EUC-KR 에서 1글자 = 2바이트
    //    29      8    생년월일     YYYYMMDD
    //    37     10    금액        우측정렬 0채움
    //    47      1    구분        1신규 2변경 3취소
    //    ------------------------------
    //    합계   47바이트  + CRLF
    // ------------------------------------------------------------
    private static final int LEN_RECORD = 47;
    private static final int OFF_기관코드 = 0,  LEN_기관코드 = 8;
    private static final int OFF_성명     = 8,  LEN_성명     = 20;
    private static final int OFF_생년월일 = 28, LEN_생년월일 = 8;
    private static final int OFF_금액     = 36, LEN_금액     = 10;
    private static final int OFF_구분     = 46, LEN_구분     = 1;

    // 우리 쪽 '적재' 저장소. 실무에서는 DB 테이블이다.
    private final List<Map<String, Object>> 수혜자원장 = Collections.synchronizedList(new ArrayList<>());
    // ★ 처리이력. 실습 6 의 멱등성 키와 완전히 같은 역할이다. 실무에서는 배치이력 테이블.
    private final Set<String> 처리이력 = Collections.synchronizedSet(new LinkedHashSet<>());

    // ============================================================
    //  0. 상대 기관 야간 배치를 대신 돌려주는 창구 (학습용)
    //     partner 컨테이너는 포트를 안 열어놨으므로 우리가 대신 불러준다.
    // ============================================================
    @SuppressWarnings("unchecked")
    public Map<String, Object> seed() {
        return restTemplate.postForObject(baseUrl + "/openapi/batch/seed", null, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> outbox() {
        return restTemplate.getForObject(baseUrl + "/openapi/batch/outbox", Map.class);
    }

    // ============================================================
    //  1. 접속
    // ============================================================

    /**
     * SFTP 채널을 연다.
     *
     * 값의 흐름 :
     *   .env  SFTP_PASSWORD
     *     -> docker-compose.yml  boot.environment.SFTP_PASSWORD
     *     -> application.properties  sftp.password=${SFTP_PASSWORD:}
     *     -> 여기 @Value 로 주입
     *     -> session.setPassword()
     *
     * ★ 실습 6 회수 : 여기에도 타임아웃이 있다.
     *   상대 SFTP 가 응답이 없는데 타임아웃이 없으면 배치가 밤새 매달린다.
     *   그리고 아침에 "배치가 안 돌았습니다" 라는 전화를 받는다.
     */
    private Session openSession() throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(user, host, port);
        session.setPassword(password);

        Properties cfg = new Properties();
        // ★★ 보안 주의 —— 여기가 오늘의 '끄면 안 되는 것' 이다.
        //   no 로 두면 상대 서버가 바뀌어도 그냥 붙는다 = 중간자 공격을 못 잡는다.
        //   TLS 에서 curl -k / 인증서 검증 끄기 와 정확히 같은 죄다.
        //   실무 정답 : ssh-keyscan 으로 상대 호스트키를 받아 known_hosts 에 넣고
        //              jsch.setKnownHosts("/app/certs/known_hosts") 로 물린 뒤 yes 로 둔다.
        //   여기서는 학습용으로 끄되, 대신 아래에서 지문(fingerprint)을 찍어 보여준다.
        cfg.put("StrictHostKeyChecking", "no");
        session.setConfig(cfg);

        session.setTimeout(5000);      // 읽기 타임아웃
        session.connect(5000);         // 연결 타임아웃

        log.info("SFTP 접속 성공 host={} user={} 서버지문={}",
                host, user, session.getHostKey().getFingerPrint(jsch));
        return session;
    }

    private ChannelSftp openSftp(Session session) throws Exception {
        ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
        ch.connect(5000);
        return ch;
    }

    // ============================================================
    //  2. 목록 — 상대 서버 폴더를 들여다본다
    // ============================================================
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("접속대상", user + "@" + host + ":" + port);
        out.put("상대폴더", remoteDir);

        Session session = null;
        ChannelSftp ch = null;
        try {
            session = openSession();
            out.put("서버지문", session.getHostKey().getFingerPrint(new JSch()));
            ch = openSftp(session);

            List<Map<String, Object>> 목록 = new ArrayList<>();
            Set<String> 이름들 = new java.util.TreeSet<>();   // 이름순으로 처리한다(순번 파일은 순서가 곧 의미다)

            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = ch.ls(remoteDir);
            for (ChannelSftp.LsEntry e : entries) {
                if (".".equals(e.getFilename()) || "..".equals(e.getFilename())) continue;
                이름들.add(e.getFilename());
            }
            for (String name : 이름들) {
                if (name.endsWith(".done")) continue;          // 마커는 목록에 따로 안 보인다
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("파일명", name);
                boolean 완료 = 이름들.contains(name + ".done");
                row.put("완료표시(.done)", 완료 ? "있음" : "없음");
                row.put("가져가도되나", name.endsWith(".dat")
                        ? (완료 ? "예" : "★ 아니오 - 상대가 아직 쓰는 중일 수 있다")
                        : "대상아님");
                목록.add(row);
            }
            out.put("파일목록", 목록);
            out.put("결과", "성공");

        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", String.valueOf(e.getMessage()));
        } finally {
            close(ch, session);
        }
        return out;
    }

    // ============================================================
    //  3. 받아오기 — 함정 ① 덜 쓰인 파일
    // ============================================================

    /**
     * @param safe true 면 '.done' 마커가 있는 파일만 가져온다.
     *
     * ★ 오늘의 함정 1.
     *   상대 배치가 500MB 짜리 파일을 쓰는 중인데 우리가 06:00 에 가져가면
     *   '앞부분만 있는' 파일을 받는다. 파일은 멀쩡히 존재하고 다운로드도 성공한다.
     *   깨진 건 다음날 민원이 들어와서야 안다.
     *
     *   해결은 프로토콜이 아니라 '약속' 으로 한다. 대표적인 두 가지 :
     *     (a) 완료표시 파일    : data.dat 를 다 쓴 뒤 data.dat.done 을 만든다  <- 여기서 쓰는 방식
     *     (b) 임시이름 후 rename : data.tmp 로 쓰고 다 쓰면 data.dat 로 이름만 바꾼다
     *                            (rename 은 같은 디스크 안에서 순간이라 중간 상태가 없다)
     *
     *   ★ 규격서에 이 약속이 없으면 그 연계는 언젠가 반드시 깨진 파일을 읽는다.
     *     설계 회의에서 물어야 할 항목이다.
     */
    public Map<String, Object> fetch(boolean safe) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("방식", safe ? "안전 - .done 있는 것만" : "★ 위험 - 보이는 대로 다 가져감");

        Session session = null;
        ChannelSftp ch = null;
        try {
            Files.createDirectories(Paths.get(localDir));
            session = openSession();
            ch = openSftp(session);

            Set<String> 이름들 = new java.util.TreeSet<>();   // 이름순으로 처리한다(순번 파일은 순서가 곧 의미다)
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = ch.ls(remoteDir);
            for (ChannelSftp.LsEntry e : entries) 이름들.add(e.getFilename());

            List<String> 받음 = new ArrayList<>();
            List<String> 건너뜀 = new ArrayList<>();

            for (String name : 이름들) {
                if (!name.endsWith(".dat")) continue;
                if (safe && !이름들.contains(name + ".done")) {
                    건너뜀.add(name + " (.done 없음)");
                    continue;
                }
                byte[] raw = download(ch, remoteDir + "/" + name);
                Files.write(Paths.get(localDir, name), raw);
                받음.add(name + " (" + raw.length + " bytes)");
            }

            out.put("받은파일", 받음);
            out.put("건너뛴파일", 건너뜀);
            out.put("저장위치", localDir + "  (호스트에서는 phase4-interop\\download)");
            out.put("결과", "성공");

        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", String.valueOf(e.getMessage()));
        } finally {
            close(ch, session);
        }
        return out;
    }

    private byte[] download(ChannelSftp ch, String remotePath) throws SftpException, java.io.IOException {
        try (InputStream in = ch.get(remotePath)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ============================================================
    //  4. 파싱 — 함정 ② 인코딩, 함정 ③ 바이트 vs 글자
    // ============================================================

    /**
     * 받아온 고정길이 전문을 뜯는다.
     *
     * @param charsetName "euc-kr" 또는 "utf-8"
     * @param byBytes     true = 바이트 위치로 자른다(맞음) / false = String.substring(틀림)
     *
     * ★ 오늘의 함정 2 — 인코딩.
     *   공공 전문은 아직도 EUC-KR(=CP949) 이 흔하다. UTF-8 로 읽으면 한글이 깨진다.
     *   그런데 '깨진다' 로 끝나지 않는다. 아래 함정 3 과 겹치면 값 자체가 밀린다.
     *
     * ★ 오늘의 함정 3 — 고정길이는 '바이트' 로 잘라야 한다.
     *   규격서의 "성명 20" 은 20글자가 아니라 20바이트다.
     *   EUC-KR 에서 한글 1글자 = 2바이트이므로
     *     "김유신" = 6바이트 = 3글자.
     *   바이트로 채운 자리를 글자로 세면 그 뒤 항목이 전부 밀린다.
     *
     *     맞음  new String(raw, 8, 20, cs)          <- 바이트 8번부터 20바이트
     *     틀림  new String(raw, cs).substring(8,28) <- 글자 8번부터 20글자
     */
    public Map<String, Object> parse(String fileName, String charsetName, boolean byBytes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("파일", fileName);
        out.put("인코딩", charsetName + (charsetName.toLowerCase().startsWith("euc") ? " (규격서대로)" : " ★ 규격서와 다름"));
        out.put("자르는기준", byBytes ? "바이트 (맞음)" : "★ String.substring - 글자 (틀림)");

        try {
            Path p = Paths.get(localDir, fileName);
            if (!Files.exists(p)) {
                out.put("결과", "파일없음 - 먼저 /interop/sftp/fetch 를 돌릴 것");
                return out;
            }
            Charset cs = Charset.forName(charsetName);
            byte[] all = Files.readAllBytes(p);

            List<Map<String, Object>> 레코드들 = new ArrayList<>();
            List<String> 불량 = new ArrayList<>();

            for (byte[] line : splitCrLf(all)) {
                if (line.length == 0) continue;

                // ★ 두 번째 방어선. 마커가 없어도 '레코드 길이' 로 잘린 파일을 잡는다.
                if (line.length != LEN_RECORD) {
                    불량.add("레코드 길이 " + line.length + "바이트 (규격 " + LEN_RECORD + "바이트) -> 잘린 파일이다");
                    continue;
                }

                Map<String, Object> r = new LinkedHashMap<>();
                if (byBytes) {
                    r.put("기관코드",  cut(line, OFF_기관코드,  LEN_기관코드,  cs));
                    r.put("성명",      cut(line, OFF_성명,      LEN_성명,      cs));
                    r.put("생년월일",  cut(line, OFF_생년월일,  LEN_생년월일,  cs));
                    r.put("금액",      cut(line, OFF_금액,      LEN_금액,      cs));
                    r.put("구분",      cut(line, OFF_구분,      LEN_구분,      cs));
                } else {
                    // ★ 회사 소스에서 제일 흔히 보이는 '틀린' 모양.
                    String s = new String(line, cs);
                    r.put("기관코드",  sub(s, OFF_기관코드,  LEN_기관코드));
                    r.put("성명",      sub(s, OFF_성명,      LEN_성명));
                    r.put("생년월일",  sub(s, OFF_생년월일,  LEN_생년월일));
                    r.put("금액",      sub(s, OFF_금액,      LEN_금액));
                    r.put("구분",      sub(s, OFF_구분,      LEN_구분));
                }
                레코드들.add(r);
            }

            out.put("정상레코드", 레코드들);
            out.put("불량레코드", 불량);
            out.put("결과", 불량.isEmpty() ? "성공" : "★ 불량 있음");

        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** 바이트 위치로 자른 뒤 문자로 바꾼다. 이 순서가 핵심이다. */
    private String cut(byte[] line, int off, int len, Charset cs) {
        return new String(line, off, len, cs).trim();
    }

    /** 글자 위치로 자른다. 한글이 섞이면 어긋난다. */
    private String sub(String s, int off, int len) {
        if (off >= s.length()) return "(범위밖)";
        return s.substring(off, Math.min(off + len, s.length())).trim();
    }

    /** CRLF 를 '바이트 단계에서' 나눈다. 문자로 바꾼 뒤 나누면 인코딩에 휘둘린다. */
    private List<byte[]> splitCrLf(byte[] all) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < all.length - 1; i++) {
            if (all[i] == '\r' && all[i + 1] == '\n') {
                out.add(java.util.Arrays.copyOfRange(all, start, i));
                start = i + 2;
                i++;
            }
        }
        if (start < all.length) out.add(java.util.Arrays.copyOfRange(all, start, all.length));
        return out;
    }

    // ============================================================
    //  5. 배치 한 판 — 받고 -> 뜯고 -> 적재하고 -> 치운다
    // ============================================================

    /**
     * @param useHistory 처리이력을 쓸지. false 면 같은 파일을 두 번 적재한다.
     *
     * ★ 실습 6 의 멱등성이 파일 세계에서는 이렇게 생겼다.
     *   HTTP : Idempotency-Key 를 보고 "이미 처리했다" 를 판단
     *   배치 : 처리이력(파일명)을 보고 "이미 처리했다" 를 판단
     *   문제도 똑같다. 배치를 두 번 돌리면 수당이 두 번 지급된다.
     *
     * ★ 그리고 처리한 파일은 '지우지 않고 옮긴다'.
     *   상대가 "그 날 파일 다시 주세요" 라고 하는 날이 반드시 오기 때문이다.
     *   archive 로 옮기면 outbox 목록에서도 사라져서 다음 배치가 다시 집지 않는다.
     */
    public Map<String, Object> run(boolean safe, boolean useHistory) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("처리이력사용", useHistory ? "on" : "★ off - 중복 적재를 재현한다");

        Session session = null;
        ChannelSftp ch = null;
        List<String> 로그 = new ArrayList<>();
        int 적재건수 = 0;

        try {
            Files.createDirectories(Paths.get(localDir));
            session = openSession();
            ch = openSftp(session);

            Set<String> 이름들 = new java.util.TreeSet<>();   // 이름순으로 처리한다(순번 파일은 순서가 곧 의미다)
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = ch.ls(remoteDir);
            for (ChannelSftp.LsEntry e : entries) 이름들.add(e.getFilename());

            for (String name : 이름들) {
                if (!name.endsWith(".dat")) continue;

                if (safe && !이름들.contains(name + ".done")) {
                    로그.add(name + " : 건너뜀 (.done 없음 - 상대가 쓰는 중)");
                    continue;
                }
                if (useHistory && 처리이력.contains(name)) {
                    로그.add(name + " : 건너뜀 (이미 처리한 파일)");
                    continue;
                }

                byte[] raw = download(ch, remoteDir + "/" + name);
                Files.write(Paths.get(localDir, name), raw);

                int ok = 0, ng = 0;
                Charset cs = Charset.forName("EUC-KR");
                for (byte[] line : splitCrLf(raw)) {
                    if (line.length == 0) continue;
                    if (line.length != LEN_RECORD) { ng++; continue; }
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("파일", name);
                    r.put("기관코드", cut(line, OFF_기관코드, LEN_기관코드, cs));
                    r.put("성명",     cut(line, OFF_성명,     LEN_성명,     cs));
                    r.put("생년월일", cut(line, OFF_생년월일, LEN_생년월일, cs));
                    r.put("금액",     cut(line, OFF_금액,     LEN_금액,     cs));
                    r.put("구분",     cut(line, OFF_구분,     LEN_구분,     cs));
                    수혜자원장.add(r);
                    ok++;
                    적재건수++;
                }
                처리이력.add(name);
                로그.add(name + " : 적재 " + ok + "건" + (ng > 0 ? " / ★ 불량 " + ng + "건" : ""));

                // 처리 끝난 파일은 archive 로 '이동'. 지우지 않는다.
                move(ch, name, 로그);
                if (이름들.contains(name + ".done")) move(ch, name + ".done", 로그);
            }

            out.put("배치로그", 로그);
            out.put("이번에적재한건수", 적재건수);
            out.put("원장누적건수", 수혜자원장.size());
            out.put("확인방법", "/interop/sftp/ledger 로 원장을 볼 것");
            out.put("결과", "성공");

        } catch (Exception e) {
            out.put("배치로그", 로그);
            out.put("결과", "실패");
            out.put("예외", e.getClass().getSimpleName());
            out.put("메시지", String.valueOf(e.getMessage()));
        } finally {
            close(ch, session);
        }
        return out;
    }

    private void move(ChannelSftp ch, String name, List<String> 로그) {
        try {
            ch.rename(remoteDir + "/" + name, remoteDir + "/archive/" + name);
        } catch (SftpException e) {
            // ★ 여기서 실패하면 다음 배치가 같은 파일을 또 집는다.
            //   '옮기기 실패' 는 조용한 사고라서 반드시 로그를 남겨야 한다.
            로그.add(name + " : ★ archive 이동 실패 - " + e.getMessage());
        }
    }

    public Map<String, Object> ledger() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("적재건수", 수혜자원장.size());
        out.put("처리이력", new ArrayList<>(처리이력));
        out.put("원장", new ArrayList<>(수혜자원장));
        return out;
    }

    public Map<String, Object> resetLedger() {
        수혜자원장.clear();
        처리이력.clear();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("결과", "우리 쪽 원장과 처리이력을 비웠다 (상대 파일은 그대로)");
        return out;
    }

    private void close(ChannelSftp ch, Session session) {
        if (ch != null) ch.disconnect();
        if (session != null) session.disconnect();
    }
}
