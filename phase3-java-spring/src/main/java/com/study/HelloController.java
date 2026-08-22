package com.study;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 1에서 파이썬으로 만든 app.py 를 자바로 그대로 옮긴 것이다.
 *
 *   @app.get("/where-am-i")   ->   @GetMapping("/where-am-i")
 *   def where_am_i():         ->   public Map<String,String> whereAmI()
 *
 * @RestController = "이 클래스는 요청을 받는 창구다 + 돌려주는 값은 JSON이다"
 *   - @Controller 만 붙이면 '화면(JSP) 이름'을 돌려주는 것으로 해석한다  <- 회사 코드가 이쪽
 *   - @RestController 는 '데이터 자체'를 돌려준다                        <- 요즘 API가 이쪽
 *   차이는 딱 그거 하나다.
 */
@RestController
public class HelloController {

    @GetMapping("/")
    public Map<String, String> home() {
        return Map.of("message", "안녕 스프링부트");
    }

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "안녕하세요 스프링");
    }

    /**
     * 어느 컨테이너가 응답했는지 보여준다. Phase 2에서 로드밸런싱을 눈으로 확인할 때 쓰던 그 엔드포인트.
     */
    @GetMapping("/where-am-i")
    public Map<String, String> whereAmI() throws Exception {
        // LinkedHashMap = 넣은 순서를 기억하는 Map. JSON 키 순서를 고정하려고 쓴다.
        Map<String, String> result = new LinkedHashMap<>();
        result.put("hostname", InetAddress.getLocalHost().getHostName());
        result.put("runtime", "java " + System.getProperty("java.version"));
        // 오늘의 핵심 증거: 지금 이 요청을 처리하고 있는 스레드 이름이 찍힌다.
        // http-nio-8080-exec-1  <- 'nio', '8080', 'exec'. 이게 톰캣의 작업 스레드 이름이다.
        // 어제 server.xml 에서 본 maxThreads="150" 이 바로 이 스레드의 최대 개수였다.
        result.put("thread", Thread.currentThread().getName());
        return result;
    }
}
