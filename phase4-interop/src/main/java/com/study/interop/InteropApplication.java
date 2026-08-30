package com.study.interop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
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
    //
    //  ★ 2026-08-30 추가 — 인증서 검증 붙이기
    //
    //  메서드 괄호 안의 두 개는 '달라는 것' 이다. 스프링이 만들어서 넣어준다.
    //    RestTemplateBuilder : RestTemplate 을 조립해주는 도구 (스프링이 기본 제공)
    //    SslBundles          : application.properties 의 spring.ssl.bundle.* 를
    //                          읽어서 담아둔 통. 여기서 이름으로 꺼낸다.
    //
    //  ★ 값이 오는 길 (이 줄이 오늘의 핵심이다)
    //
    //    certs/ca.crt
    //      -> keytool -importcert
    //        -> certs/truststore.p12
    //          -> application.properties
    //             spring.ssl.bundle.jks.[partner].truststore.location
    //                                    └──┬──┘
    //            -> SslBundles.getBundle("partner")   <- 이름이 여기서 만난다
    //              -> RestTemplate 안의 SSLContext
    //                -> 소켓 핸드셰이크에서 상대 인증서의 서명을 검증
    //
    //  ★ 우리가 안 한 것: RestTemplate 을 쓰는 코드(PartnerClient)는 한 글자도
    //    안 고쳤다. 검증은 '부품을 조립하는 이 자리' 에서 붙는다.
    //    회사 소스에서도 TLS 설정은 Client 가 아니라 Config 클래스에 있다.
    // ============================================================
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder, SslBundles sslBundles) {
        return builder
                .setSslBundle(sslBundles.getBundle("partner"))
                // ★ 2026-08-30 여기서 컴파일 에러를 냈다.
                //   스프링부트 3.3 의 메서드 이름은 setConnectTimeout / setReadTimeout 이다.
                //   (connectTimeout 으로 짧아진 건 3.4 부터. 버전이 다르면 이름도 다르다)
                //   에러 메시지가 정확히 그렇게 말해줬다:
                //     cannot find symbol / symbol: method connectTimeout(Duration)
                //     location: class RestTemplateBuilder
                //   -> "그 클래스에 그런 이름의 메서드가 없다" 는 뜻.
                .setConnectTimeout(Duration.ofSeconds(3))   // 연결 맺는 데 3초
                .setReadTimeout(Duration.ofSeconds(5))      // 응답 기다리는 데 5초
                .build();
    }
}
