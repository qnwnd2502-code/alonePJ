# 구형 전자정부 구조 읽기 연습 (2026-08-27, Phase 3 5일차)

`@SpringBootApplication` 이 없는 프로젝트다. 회사에서 만나는 모양이다.
이 폴더는 `src/main/java` 밖에 있어서 **컴파일되지 않는다.** 읽기 전용이다.

## 파일 4개의 역할

| 파일 | Spring Boot 에서는 | 이 프로젝트에서는 |
|---|---|---|
| `webapp/WEB-INF/web.xml` | (없음. 자동) | 어떤 주소를 누가 받나, 필터, 리스너 |
| `webapp/WEB-INF/config/.../egov-com-servlet.xml` | `@SpringBootApplication` + 자동설정 | Controller 스캔, viewResolver, 예외화면 |
| `resources/egovframework/spring/context-common.xml` | `application.properties` + 자동설정 | Service/DAO 스캔, DataSource, MyBatis, 공통컴포넌트 |
| `webapp/WEB-INF/jsp/sample/egovSampleList.jsp` | (요즘은 화면을 프론트가 따로) | 화면 |

## URL 추적 지도 -- 이게 오늘의 결론

```
브라우저: /egovSampleList.do
   |
   | (1) web.xml : url-pattern *.do  ->  servlet-name action
   |              그 servlet 의 contextConfigLocation 을 읽는다
   v
DispatcherServlet ("action")
   |
   | (2) egov-com-servlet.xml : component-scan base-package="egovframework"
   |                            + include-filter @Controller
   |              그 중 RequestMapping("/egovSampleList.do") 붙은 메서드를 찾는다
   v
EgovSampleController.selectSampleList(...)
   |
   | (3) Resource(name="sampleService") 로 Service 를 받는다
   |              그 Service 는 루트 컨테이너(context-common.xml)에 있다
   |
   | (4) return "sample/egovSampleList";
   |              viewResolver 의 prefix + 이것 + suffix
   v
/WEB-INF/jsp/sample/egovSampleList.jsp
```

### 거꾸로 타는 법 (실무에서 더 자주 쓴다)

```
"이 화면 문구 고쳐주세요"
  -> 화면 문구로 grep         -> JSP 파일
  -> return "그 경로" 로 grep -> Controller
  -> Controller 가 쓰는 Service
  -> Service 가 쓰는 Mapper XML 의 SQL
```
어노테이션이 없어도 상관없다. 문자열로 찾기 때문이다.

## 컨테이너가 두 개다 -- 제일 헷갈리는 지점

```
루트 컨테이너   (ContextLoaderListener + context-common.xml)
    = 본사. Service, DAO, DataSource, 트랜잭션. 손님을 직접 안 만난다
        |  부모
서블릿 컨테이너 (DispatcherServlet + egov-com-servlet.xml)
    = 영업점 창구. Controller, viewResolver. 손님을 만난다

창구는 본사 자원을 쓸 수 있다  <- Controller 가 Service 를 주입받는 이유
본사는 창구를 모른다          <- 반대 방향은 안 된다
```

두 `component-scan` 이 `include-filter` / `exclude-filter` 로 정확히 반씩 나눠 갖는다.
안 나누면 Service 가 두 컨테이너에 각각 하나씩 총 2개 생기고,
Controller 가 트랜잭션 안 걸린 쪽을 잡으면 **에러 없이 롤백이 안 된다.**
전자정부 프로젝트의 유명한 함정. 에러가 안 나는 게 제일 무섭다.

## 증상별로 어느 XML 을 열어야 하나

| 증상 | 열 파일 |
|---|---|
| 주소 불렀는데 404, 화면 안 뜸 | `**-servlet.xml` |
| Service/DAO 주입 실패, DB 접속 실패, 트랜잭션 안 걸림 | `context-*.xml` |
| JSP 경로가 안 맞음 | `**-servlet.xml` 의 viewResolver |
| 이 URL 이 어느 자바로 가는지 모르겠음 | web.xml -> **-servlet.xml -> base-package |
| 등록한 한글이 물음표로 저장됨 | web.xml 의 encodingFilter |

## 자주 보는 이름들

| 이름 | 뜻 |
|---|---|
| `load-on-startup` | 서버 뜰 때 미리 만들어둔다. 없으면 첫 사용자만 느리다 |
| `prefix` / `suffix` | Controller 의 return 문자열 앞뒤에 붙여 JSP 경로를 만든다 |
| `/WEB-INF/` 안 | 브라우저가 직접 못 연다. Controller 를 거쳐야만 화면이 나온다 |
| `egovframework.rte.fdl.**` | 전자정부가 준 공통 부품(ID생성, 암호화, 파일업로드...). 안 열어봐도 된다 |
| `oracle.jdbc.driver.OracleDriver` | JDBC 통역사. 회사는 오라클 아니면 티베로 |
| `${row.USE_YN}` 이 대문자 | resultType=map + 오라클/티베로. DB 컬럼명이 JSP 까지 관통한다 |

## XML 은 서버 뜰 때 한 번만 읽는다

요청은 XML 로 가지 않는다. **자바 객체(DispatcherServlet)** 에게 간다.
XML 은 그 객체가 시작할 때 읽는 설명서다.
-> 그래서 XML 을 고치면 **서버를 재시작**해야 반영된다.
   (4일차의 MemberMapper.xml 도 같은 이유였다)
