package com.study;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestParam;
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
    // 타입이 구현체가 아니라 '인터페이스' 다. Controller 는 MemberServiceImpl 이라는
    // 이름을 몰라도 된다. 전자정부 소스의 Controller 가 전부 이 모양이다.
    //
    // @Autowired  : '규격(타입)' 으로 찾는다. 후보가 2개 이상이면 못 고르고 죽는다
    //               -> 그때 @Qualifier("빈이름") 로 지목해준다
    // @Resource   : 처음부터 '이름' 으로 찾는다. 위 두 줄을 한 줄로 합친 셈이다
    //               -> 전자정부 소스가 이걸 쓴다
    // 이름 "memberServiceImpl" 은 클래스 이름 첫 글자만 소문자로 바꾼 것.
    // @Service 가 빈을 담을 때 스프링이 자동으로 붙여주는 기본 이름이다.
    @Resource(name = "memberServiceImpl")
    private MemberService memberService;

    @GetMapping("/")
    public Map<String, String> home() {
        return Map.of("message", "안녕 스프링부트");
    }

    @GetMapping("/hello")
    public Map<String, String> hello(@RequestParam String name) {
        // @RequestParam = 주소 뒤 ?name=ooo 값을 받아온다
        String result = memberService.greet(name);
        return Map.of("message", result);
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
        // 인터페이스 자리에 '실제로' 뭐가 꽂혀 있는지 실행 중에 물어본다.
        result.put("service", memberService.getClass().getSimpleName());
        return result;
    }
}
