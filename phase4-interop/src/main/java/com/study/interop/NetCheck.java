package com.study.interop;

import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ============================================================
//  개통 확인 도구.  (실습 12)
//
//  현장에서 쓰는 명령을 자바로 옮긴 것이다.
//    리눅스   nc -zv 10.20.30.100 443
//             timeout 3 bash -c '</dev/tcp/10.20.30.100/443'
//    윈도우   Test-NetConnection 10.20.30.100 -Port 443
//
//  ★ TCP 연결만 해본다. HTTP 는 보내지 않는다.
//    "길이 뚫렸나" 와 "서비스가 제대로 답하나" 를 섞지 않기 위해서다.
//    개통 확인은 항상 아래 순서로, 한 층씩 올라간다.
//      ① 이름풀이(DNS)  ② TCP 연결  ③ TLS  ④ HTTP·인증  ⑤ 업무 전문
// ============================================================
@Service
public class NetCheck {

    private static final int 연결대기ms = 3000;

    public Map<String, Object> tcp(String host, int port) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("확인대상", host + ":" + port);
        out.put("이서버의IP", 내IP목록());

        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 연결대기ms);
            out.put("결과", "연결됨");
        } catch (Exception e) {
            out.put("결과", "실패");
            out.put("예외", e.getClass().getName());
            out.put("메시지", String.valueOf(e.getMessage()));
        }
        out.put("걸린시간ms", System.currentTimeMillis() - start);
        return out;
    }

    /** 이 서버에 붙어 있는 IPv4 주소들. 리눅스의 `hostname -I` / `ip addr` 와 같다. */
    private List<String> 내IP목록() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) ips.add(a.getHostAddress() + " (" + ni.getName() + ")");
                }
            }
        } catch (Exception e) {
            ips.add("조회 실패: " + e.getMessage());
        }
        return ips;
    }
}
