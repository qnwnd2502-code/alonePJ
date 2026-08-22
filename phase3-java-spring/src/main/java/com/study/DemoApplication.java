package com.study;
// package = 이 파일이 사는 폴더 주소. 반드시 실제 폴더 구조(src/main/java/com/study)와 같아야 한다.
// 안 맞으면 컴파일 에러가 난다. 회사 코드에서 파일을 찾을 때 이 줄만 보면 위치를 알 수 있다.

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 이 앱의 시작점. 파일은 딱 이거 하나뿐이고, 여기서 서버가 켜진다.
 *
 * @SpringBootApplication 한 줄이 사실 세 가지를 한다:
 *   1) 이 폴더(com.study) 아래를 전부 뒤져서 @RestController, @Service 같은 게 붙은 클래스를 찾아 모은다
 *   2) 찾은 것들을 스프링이 대신 new 해서 상자에 담아둔다  <- 이 상자가 'IoC 컨테이너'
 *   3) 톰캣을 켜고 8080 포트를 연다
 *
 * 회사(전자정부프레임워크)에는 이 어노테이션이 없다.
 * 대신 web.xml + applicationContext.xml 에 같은 내용을 손으로 적어놨다.
 * 하는 일은 완전히 같고, '어디에 적느냐'만 다르다.
 */
@SpringBootApplication
public class DemoApplication {

    // main = 자바 프로그램의 유일한 출발점. 자바는 무조건 여기서 시작한다.
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
