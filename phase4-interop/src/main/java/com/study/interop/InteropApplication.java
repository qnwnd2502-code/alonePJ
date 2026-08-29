package com.study.interop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
public class InteropApplication {

    public static void main(String[] args) {
        SpringApplication.run(InteropApplication.class, args);
    }

    // ============================================================
    //  RestTemplate 을 빈으로 등록한다.
    //  Phase 3 에서 배운 것: @Bean 이 붙은 메서드의 리턴값이 컨테이너에 담기고,
    //  필요한 곳에서 @Resource / @Autowired 로 꺼내 쓴다.
    //
    //  ★ 타임아웃을 반드시 준다. 이게 연계의 1번 안전장치다. ★
    //    안 주면 기본값이 '무한 대기'다. 상대 기관이 응답을 안 주면
    //    우리 톰캣 스레드가 하나씩 붙잡혀 있다가 결국 전부 소진되고
    //    -- 상대 기관 장애가 우리 서비스 전체 장애가 된다.
    //    (Phase 2 에서 본 504 Gateway Timeout 의 자바 쪽 대응물)
    // ============================================================
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));   // 연결 맺는 데 3초
        factory.setReadTimeout(Duration.ofSeconds(5));      // 응답 기다리는 데 5초
        return new RestTemplate(factory);
    }
}
