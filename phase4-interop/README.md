# Phase 4 — 시스템 연계 실습

포트 **9600**. 컨테이너 3대가 뜬다.

```
[boot :9600]  우리 시스템 (Spring Boot)
     |
     +-- HTTP ------> [partner:8000]     상대 기관 API 서버 (FastAPI)
     |
     +-- JDBC ------> [partner-db:5432]  상대 기관 DB (PostgreSQL)
```

## 기동

```powershell
cd D:\claude\phase4-interop
docker compose up -d --build
docker compose logs boot -f          # "Started InteropApplication" 나오면 Ctrl+C
```

`.env` 가 필요하다. `.env.example` 을 복사해서 값을 채운다. (`.env` 는 커밋 금지)

## 실습 주소

| 주소 | 확인할 것 |
|---|---|
| `/interop/list-urlkey` | API Key 를 URL 에 → 로그에 평문으로 남는다 |
| `/interop/list-headerkey` | API Key 를 헤더에 → 결과 동일, 로그에 안 남는다 |
| `/interop/echo-urlkey` | ★ 상대가 '받은 그대로' 를 돌려준다. 요청첫줄에 키가 있다 |
| `/interop/echo-headerkey` | ★ 헤더전부 에 authorization 이 있다. 두 개를 나란히 비교 |
| `/interop/list-nokey` | 401 → 실패를 3종류로 분류 |
| `/interop/open-by-path` | 경로만으로는 파일을 못 연다 (exists=false) |
| `/interop/download?atchFileId=FILE_000000000000123` | 제대로 가져오는 법 |
| `/interop/db-direct` | 상대 테이블을 직접 SELECT → 결합도 높음 |
| `/interop/db-view` | 상대가 열어준 뷰만 SELECT → 뷰가 계약서 |

## 값이 코드까지 오는 길 (설정 추적)

```
.env
  PARTNER_DB_NAME=partnerdb
     |
docker-compose.yml  boot 서비스 environment
  PARTNER_DB_URL: jdbc:postgresql://partner-db:5432/${PARTNER_DB_NAME}
     |
컨테이너 안 환경변수
     |
application.properties
  spring.datasource.url=${PARTNER_DB_URL:...}
     |   ★ 이름이 Spring 규약이라 자동으로 집어간다
Spring Boot 자동설정 -> DataSource 빈 -> JdbcTemplate 빈
     |
PartnerDbClient.java
  @Resource private JdbcTemplate jdbcTemplate;
```

각 칸을 눈으로 확인하는 명령:
```powershell
docker compose exec boot env | Select-String PARTNER          # 환경변수까지 왔나
docker compose logs boot | Select-String "HikariPool|jdbc:"   # 실제로 어디에 붙었나
```

### 우리가 지은 이름 vs 스프링 규약 이름

| | `partner.base-url` | `spring.datasource.url` |
|---|---|---|
| 누가 지었나 | 우리 | Spring |
| 코드에서 | `@Value("${partner.base-url}")` 로 직접 꺼냄 | 꺼내는 코드가 없음 |
| 왜 | 우리가 지은 이름은 Spring 이 모른다 | 정해진 이름이라 알아서 집어간다 |

**독해 규칙**: "이 값이 어디서 왔지?" 싶으면
1. 그 파일에서 `@Value("${...}")` 를 찾는다 -> 있으면 properties 를 본다
2. 없으면 규약 이름이다 -> 그 부품이 자동으로 받은 것
3. 최종 확인은 항상 기동 로그

(구형 전자정부는 `context-common.xml` 에 손으로 다 적혀 있어서 오히려 찾기 쉽다)

## DB 직접 연계 실험 (2026-08-30 직접 재현)

상대 기관 DBA 가 우리한테 말 없이 컬럼 이름을 바꾼 상황:

```powershell
docker compose exec partner-db psql -U partner_ro -d partnerdb -c "ALTER TABLE comtnfiledetail RENAME COLUMN orignl_file_nm TO file_nm;"
```

| | 결과 |
|---|---|
| `/interop/db-direct` | **HTTP 500** — `ERROR: column "orignl_file_nm" does not exist` |
| `/interop/db-view` | **정상** — 뷰가 컬럼을 이름이 아니라 내부 번호로 기억하므로 따라간다 |

되돌리기:
```powershell
docker compose exec partner-db psql -U partner_ro -d partnerdb -c "ALTER TABLE comtnfiledetail RENAME COLUMN file_nm TO orignl_file_nm;"
```

**결론**: 기관 간 DB 직접 연계는 상대 테이블 구조에 우리가 묶인다.
상대는 우리가 보고 있는 줄 모르므로 예고 없이 깨진다. 그건 상대 잘못이 아니다.
실무에서 'DB 직접 연계' 라고 부르는 것은 대부분 **읽기전용 계정 + 뷰** 형태다.

---

# 실습 3 — mTLS (2026-08-30, 진행 중)

## 지금 어디까지 왔나

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | CA 만들고, 상대 기관 서버 인증서 발급, nginx 에 장착 | ✅ |
| 2 | 자바가 거부 (`PKIX path building failed`) → truststore 에 CA 등록 → 통과 | ✅ |
| 3 | `ssl_verify_client on` → 상대가 **우리** 신분증을 요구 → keystore | ✅ |
| 4 | 상대 nginx 가 `X-Client-DN` 으로 우리 신원을 뒷단 앱에 넘기는 것 확인 | ✅ |

## 누가 누구인가 (헷갈리면 여기를 본다)

```
   브라우저                우리 서버                    상대 기관
      │                       │                            │
      │  http :9600           │                            │
      ├──────────────────────>│  [boot]                    │
      │                       │                            │
      │                       │  https :8443               │
      │                       ├───────────────────────────>│  [partner-gw : nginx]
      │                       │   ↑ 여기가 오늘 배운 구간     │         │ http :8000
      │                       │                            │         v
      │                       │                            │  [partner : FastAPI]
```

- **9600** = 우리 앱. 우리가 만든 화면을 보는 문
- **8443** = 상대 기관 웹서버. **우리 코드가** 들어가는 문 (브라우저용이 아니다)

## 인증서 만드는 명령 (전부 boot 컨테이너 안에서)

```powershell
docker compose exec boot mkdir -p /app/certs

# 1) CA (인증기관). -x509 = 스스로에게 서명한 완성 인증서
docker compose exec boot openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes -keyout /app/certs/ca.key -out /app/certs/ca.crt -subj "/C=KR/O=Study Private CA/CN=Study-Root-CA"

# 2) 상대 기관의 신청서(CSR). -x509 가 없으면 .csr 이 나온다
docker compose exec boot openssl req -newkey rsa:2048 -nodes -keyout /app/certs/partner.key -out /app/certs/partner.csr -subj "/C=KR/O=Partner Agency/CN=partner-gw"

# 3) CA 가 서명 -> 신분증 완성. -extfile 로 SAN 을 넣는다(없으면 자바가 거부)
docker compose exec boot openssl x509 -req -in /app/certs/partner.csr -CA /app/certs/ca.crt -CAkey /app/certs/ca.key -CAcreateserial -days 825 -sha256 -extfile /app/partner-gw/partner-san.ext -out /app/certs/partner.crt

# 4) 신분증 읽어보기. subject != issuer 면 남이 발급해준 정상 인증서
docker compose exec boot openssl x509 -in /app/certs/partner.crt -noout -subject -issuer -ext subjectAltName

# 5) 자바가 읽는 '신뢰 목록' 만들기. 넣는 건 partner.crt 가 아니라 ca.crt 다
docker compose exec boot keytool -importcert -noprompt -alias study-root-ca -file /app/certs/ca.crt -keystore /app/certs/truststore.p12 -storetype PKCS12 -storepass changeit
```

`certs/` 는 `.gitignore` 로 막혀 있다. 개인키가 들어 있기 때문. 위 명령으로 언제든 다시 만든다.

## 같은 사건, 세 가지 말투

| 누가 | 뭐라고 하나 |
|---|---|
| 브라우저 | `ERR_CERT_AUTHORITY_INVALID` |
| curl | `curl: (60) unable to get local issuer certificate` |
| 자바 | `PKIX path building failed: unable to find valid certification path` |

셋 다 뜻은 하나 — **"너를 보증한 CA 를 내가 안 믿는다."**
그리고 셋 다 **인증서 잘못이 아니라 '읽는 쪽의 신뢰 목록이 비어서'** 나는 것이다.

★ **프로그램마다 신뢰 목록이 따로다.** 자바는 자기 것, curl 은 OS 것, 브라우저는 또 자기 것.
   -> 실무의 "curl 로는 되는데 자바에서만 안 돼요" 가 여기서 나온다.

🚨 `curl -k` 와 자바의 검증 끄는 TrustManager 는 **원인 확인용 1회**로만.
   암호화는 그대로지만 ③인증이 꺼져서 **공격자와 암호화**될 수 있다.

## 값이 코드까지 오는 길

```
certs/ca.crt
  -> keytool -importcert
    -> certs/truststore.p12
      -> application.properties : spring.ssl.bundle.jks.[partner].truststore.location
        -> InteropApplication   : sslBundles.getBundle("partner")   <- 이름이 여기서 만난다
          -> RestTemplate 안의 SSLContext
            -> 핸드셰이크에서 상대 인증서의 서명 검증
```

★ **PartnerClient.java 는 한 글자도 안 고쳤다.**
   TLS 설정은 '요청 보내는 코드' 가 아니라 '부품 조립하는 자리(Config)' 에 붙는다.
   회사 소스에서도 거기부터 찾을 것.

## 오늘 밟은 함정 2개

| 증상 | 원인 | 규칙 |
|---|---|---|
| 환경변수를 고쳤는데 기본값이 찍힘 | `docker compose restart` 는 compose 파일을 다시 안 읽는다 | **compose 고쳤으면 `up -d`, 소스만 고쳤으면 `restart`** |
| `cannot find symbol: connectTimeout` | 스프링부트 3.3 은 `setConnectTimeout`. 3.4 부터 `connectTimeout` | **버전이 다르면 메서드 이름도 다르다.** 에러가 클래스명까지 알려준다 |

★ 로그에서 `at` 으로 시작하는 줄은 전부 무시. 원인은 `Caused by:` 와 `at` 아닌 줄에 있다.

---

# 실습 3 후반 — mTLS 완성 (2026-09-01)

## 우리 클라이언트 인증서 만드는 명령

```powershell
# 1) 우리 개인키 + 신청서. -subj 의 O=/CN= 가 곧 우리 신원이 된다
docker compose exec boot openssl req -newkey rsa:2048 -nodes -keyout /app/certs/client.key -out /app/certs/client.csr -subj "/C=KR/O=Our SI Company/CN=interop-client"

# 2) 같은 CA 가 서명. 서버용과 다른 건 -extfile 하나 (clientAuth)
docker compose exec boot openssl x509 -req -in /app/certs/client.csr -CA /app/certs/ca.crt -CAkey /app/certs/ca.key -CAcreateserial -days 825 -sha256 -extfile /app/partner-gw/client-san.ext -out /app/certs/client.crt

# 3) 개인키 + 신분증 + 발급자 를 하나로 묶어 keystore 만들기
docker compose exec boot openssl pkcs12 -export -inkey /app/certs/client.key -in /app/certs/client.crt -certfile /app/certs/ca.crt -name interop-client -out /app/certs/keystore.p12 -passout pass:changeit

# 4) 확인. TLS Web Client Authentication 이 보여야 한다
docker compose exec boot openssl x509 -in /app/certs/client.crt -noout -subject -issuer -ext extendedKeyUsage
```

## truststore vs keystore

| | 안에 든 것 | 역할 | 새면 |
|---|---|---|---|
| `truststore.p12` | 공개 인증서만 (ca.crt) | **남을 검증** | 별일 없음 |
| `keystore.p12` | **개인키 + 우리 인증서** 🔑 | **나를 증명** | **끝. 남이 우리 행세를 한다** |

`.gitignore` 에 `*.p12` 를 넣은 이유가 keystore 때문이다. `.key` 와 같은 급으로 취급한다.

## 양쪽 설정의 대칭

```
우리 쪽 (application.properties)          상대 쪽 (nginx.conf)
─────────────────────────────────         ──────────────────────────────
truststore.location = truststore.p12  ←→  ssl_certificate     partner.crt
  (ca.crt 를 넣어둠 = 이 CA 를 믿는다)        (자기 신분증을 내민다)

keystore.location   = keystore.p12    ←→  ssl_client_certificate ca.crt
  (우리 신분증을 내민다)                     (이 CA 를 믿는다)
                                          ssl_verify_client on
                                            (신분증 없으면 거절)
```

★ **양쪽이 같은 CA 를 믿기로 합의한 것** 이 mTLS 의 전부다.
   실무에서는 이 합의를 연계 규격서에 적고 인증서를 주고받는 일로 한다.

## nginx 의 400 두 종류 — 구분해야 한다

| 상황 | nginx 가 하는 말 | 원인 |
|---|---|---|
| 신분증을 **안 보냄** | `400 No required SSL certificate was sent` | 우리 설정에 keystore 가 없다 |
| 보냈는데 **CA 가 다름** | `400 The SSL certificate error` | 발급받은 곳이 상대가 믿는 곳이 아니다 |

★ **자바 예외로 나면 우리가 거절한 것, HTTP 상태코드로 오면 상대가 거절한 것.**
   지난번 PKIX 는 연결 자체가 안 맺어진 것(응답 없음), 오늘 400 은 연결은 됐고 거절 응답을 받은 것.

## X-Client-DN — 상대 앱은 인증서를 몰라도 된다

```
우리   : keystore.p12 안의 client.crt 를 핸드셰이크에서 제시
         ↓
nginx  : 검증하고 $ssl_client_s_dn 에 담아 X-Client-DN 헤더로 뒷단에 붙여줌
         ↓
상대 앱: 헤더 한 줄만 읽으면 "누가 왔는지" 안다
```

실제로 받은 값:
```
x-client-dn     = CN=interop-client,O=Our SI Company,C=KR
x-client-verify = SUCCESS
```

★ **독해 신호**: 전자정부 소스에서 `request.getHeader("X-Client-DN")` 을 보면
   "앞에 mTLS 하는 웹서버가 있다" 는 뜻이다. 자바 코드에는 인증서 얘기가 한 줄도 없을 수 있다.

## API Key 와 클라이언트 인증서의 차이

| | API Key | 클라이언트 인증서 |
|---|---|---|
| 증명 방식 | **아는 것** (비밀값) | **가진 것** (개인키) |
| 비밀을 누가 갖나 | **양쪽이 같은 값**을 갖는다 | 개인키는 **우리만**. 상대는 검증만 |
| 네트워크에 나가나 | **매 요청마다 실제 값이 나간다** | **개인키는 절대 안 나간다** (서명만) |
| 로그에 남으면 | 그 자체가 유출 (1일차 실습) | DN 은 남아도 비밀이 아니다 |
| 상대 DB 가 털리면 | **우리 키도 털린다** | 우리 것은 안전 (공개 인증서만 있음) |
| 폐기 | 재발급 + 양쪽 설정 변경 | CRL/OCSP 로 그 인증서만 폐기, CA 유지 |
| 기관이 100곳이면 | 상대가 키 100개를 관리 | **CA 1개만 믿으면 됨** |
| 무엇을 식별하나 | 보통 **업무/계정 단위** | 보통 **기관/서버 단위** |

★ 둘은 대체재가 아니다. **오늘 우리는 실제로 둘을 같이 썼다.**
```
클라이언트 인증서 : "우리 회사 서버가 맞다"        <- 채널(회선) 단위 인증
Authorization 헤더: "이 업무를 볼 권한이 있다"     <- 요청 단위 인가
```
공공 연계에서 mTLS 는 '기관 대 기관 회선을 믿는 층' 이고,
그 위에 API Key/토큰이 '누가 무엇을 할 수 있나' 를 얹는다. 층이 다르다.

## 오늘 밟은 함정

| 증상 | 원인 | 규칙 |
|---|---|---|
| 파일이 있는데 `FileNotFoundException` | `.properties` **줄 끝 공백**이 값에 포함됨 | 자바가 값을 `'...'` 로 감싸주면 **따옴표 안쪽 양 끝**을 본다 |

`nginx -s reload` vs `restart`: reload 는 설정만 다시 읽어 **무중단**. 운영 웹서버는 reload 가 기본.
자바 앱은 reload 가 없어서 `restart` 뿐이다.

---

# 실습 4 — 전문 위·변조 방지 (HMAC), 2026-09-03

## 왜 TLS 만으로 부족한가

```
[우리] ──https──> [DMZ 웹서버] ──http──> [연계서버/ESB] ──> [업무시스템]
                    TLS 종료 (1)            TLS 종료 (2)
```

TLS 무결성은 **구간(hop)마다** 보장된다. 종료 지점 이후는 평문이고, 그 뒤에서
내용이 바뀌어도 우리는 모른다. 그래서 **출발지에서 도착지까지** 를 지키는
별도 장치가 필요하다 = **종단간(end-to-end) 무결성** = HMAC.

## HMAC 은 암호화가 아니다

```
우리 쪽                              상대 쪽
본문 + 비밀키                         받은 본문 + (같은) 비밀키
    v HMAC 계산                           v HMAC 계산   <- 똑같은 계산을 다시 한다
  a3f9c8...  ──헤더로 보냄──>            a3f9c8...
                                          v
                                    받은 값과 비교 -> 같으면 통과
```

★ **복호화가 아니라 재계산이다.** 해시는 되돌릴 수 없고, 되돌릴 필요도 없다.
   그래서 본문은 **평문 그대로** 보낸다. 숨기는 건 TLS 가 한다.

★ **해시만 보내면 무의미하다.** 공격자가 내용을 바꾸고 해시도 다시 계산하면 되니까.
   비밀키가 곧 **'계산할 자격'** 이다.

## 실습 창구

| 주소 | 무엇을 비틀었나 | 결과 |
|---|---|---|
| `/interop/hmac-ok` | (정상) | 200 통과 |
| `/interop/hmac-ascii` | 본문에 한글 없음 (정상) | 200 통과 |
| `/interop/hmac-tampered` | 서명은 원본, **본문만 바꿔치기** | **401 서명 불일치** |
| `/interop/hmac-replay-v1` | 10분 전 요청을 그대로 재전송 | 😱 **200 통과 (문제!)** |
| `/interop/hmac-replay-v2` | 같은 것을 timestamp 검증 창구로 | **401 거절** |

우리 창구는 브라우저에 200 으로 답하지만 본문 안에 `"상태코드": 401` 이 담긴다.
`PartnerClient` 가 `HttpClientErrorException` 을 잡아 사람이 읽을 형태로 바꾼 것이다.
(1일차 실패 3분류: **4xx = 재시도 무의미**)

## canonical string — 서명할 대상의 조립 규격

```
timestamp \n method \n path \n body
```

규격서에 이 순서가 반드시 적혀 있다. **한 글자라도 다르면 서명이 안 맞는다.**
양쪽 코드가 짝이다: `HmacSigner.canonical()`  <->  `app.py 의 build_canonical()`

## ★ HMAC 이 안 맞을 때 진단표

| 증상 | 원인 | 왜 |
|---|---|---|
| **한글 든 요청만** 실패 | 🎯 **인코딩** | 영문·숫자는 어느 인코딩이든 바이트가 같다. 한글만 갈린다 |
| **전부** 실패 | 비밀키 불일치 or 조립 순서 | 내용과 무관하게 계산 자체가 다르다 |
| **특정 경로만** 실패 | 조립 순서 (path·쿼리 처리) | 그 경로에서만 조립 결과가 어긋난다 |

★ 그래서 `getBytes()` 를 빈 괄호로 두면 안 된다. 반드시 `getBytes(StandardCharsets.UTF_8)`.
  자바 18 부터 기본값이 UTF-8 로 통일됐지만 **구형 전자정부는 자바 8/11 에서 돈다.**
  거기서는 윈도우(cp949) 개발 PC 와 리눅스(UTF-8) 운영 서버의 서명이 달라진다.
  -> **"개발에선 되는데 운영에서 401"** 의 단골 원인.

## HMAC 이 못 막는 것 — 재전송 공격

`hmac-replay-v1` 이 통과하는 것이 증거다. **정상 요청을 그대로 복사해 다시 보내면
서명도 그대로 유효하다.** HMAC 은 '안 바뀌었나' 만 보고 '처음인가' 는 안 본다.

그래서 규격서에는 HMAC 과 **timestamp** 가 거의 항상 같이 있다(v2 가 그것).
단 timestamp 는 재전송 '창(window)' 을 좁히는 것이고, 그 안에서는 여전히 통과한다.
완전히 막으려면 **nonce(1회용 난수)** 를 서버가 기억해서 재사용을 거부해야 한다.

## HMAC(대칭) vs RSA 전자서명(비대칭)

| | HMAC | RSA 전자서명 |
|---|---|---|
| 답하는 질문 | **안 바뀌었나** | **안 바뀌었나 + 누가 보냈나** |
| 속도 | 아주 빠름 | 수백~수천 배 느림 |
| 키 관리 | 값 하나 공유 | CA·만료·CRL 체계 필요 |
| 부인방지 | ❌ (양쪽이 키를 가져서 누가 만들었는지 증명 못 함) | ✅ |

★ **우열이 아니라 층이 다르다.** 이 프로젝트는 셋을 쌓아 쓰고 있다:

```
HMAC (대칭)          - 전문이 안 바뀌었나        <- 실습 4
mTLS = RSA (비대칭)  - 접속하는 게 누구인가      <- 실습 3
TLS 암호화           - 엿듣기 차단              <- Phase 2
```

★ **JWT 로 이어진다**: `HS256` = HMAC-SHA256(오늘) / `RS256` = RSA-SHA256(어제).
  JWT 를 쓸 때 이 둘을 골라야 하는데, 양쪽을 다 만들어봤으니 이해하고 고를 수 있다.

★ 부인방지가 법적으로 필요한 자리(전자문서 유통·결재)에 쓰는 것이
  한국 공공의 **GPKI 행정전자서명** 이다.

## 오늘 얻은 부수 소득 — Apache HttpClient 5

`pom.xml` 에 추가했다. 이유 2개:

1. `RestTemplate` 의 기본 구현(JDK 내장)은 **커넥션 풀이 없다.** 요청마다
   TCP+TLS 핸드셰이크를 새로 한다. 연계 호출이 잦으면 이것만으로 느려진다.
2. **오류 응답(4xx/5xx)의 본문을 안정적으로 읽어준다.** 기본 구현은 POST 오류에서
   본문을 흘려서 `401 Unauthorized: [no body]` 만 나왔다 — 실제로 겪었다.
   상대가 왜 거절했는지 못 읽으면 장애 분석이 불가능하다.

클래스패스에 있으면 스프링부트가 알아서 고른다. **자바 코드 수정은 없었다.**

`nginx.conf` 에 `proxy_http_version 1.1;` 도 추가했다. nginx 기본값이 1.0 인데,
1.0 은 keep-alive 가 없어 뒷단과 매 요청마다 연결을 새로 맺는다.

---

# 실습 5 — OAuth2 / JWT (2026-09-06)

## 무엇이 달라졌나

```
지금까지 : 매 요청마다 API Key 원본을 실어 보냄  (비밀값이 매번 나간다)
오늘부터 : (1) 한 번 자격증명으로 토큰을 받고
           (2) 그 뒤엔 토큰만 보냄
           (3) 만료되면 다시 받음
```

토큰은 **유효기간 있는 임시 신분증**이다. 새어도 몇 분 뒤 쓸모없어진다.
대신 **재발급 로직**이 필요해진다(회사 소스의 `TokenManager` 가 하는 일).

## OAuth2 와 JWT 는 다른 층이다

```
OAuth2 = 토큰을 어떻게 주고받나 (절차)   <- RFC 6749
JWT    = 그 토큰이 어떻게 생겼나 (형식)
```

연계는 사람 로그인이 없으므로 **client_credentials** 흐름을 쓴다.
(화면의 '네이버로 로그인' 은 authorization_code 로 다른 흐름이다)

## ★ 실무 함정 — 토큰 창구만 form 형식이다

```java
headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);   // JSON 이 아니다
MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
form.add("grant_type", "client_credentials");
```

다른 API 는 전부 JSON 인데 **토큰 창구만 form** 이다. RFC 6749 가 그렇게 정했다.
JSON 을 보내면 400 이 난다.
(상대 서버 쪽도 마찬가지다. FastAPI 에 `python-multipart` 가 없어서 500 이 났다)

응답 항목 이름(`access_token` `token_type` `expires_in`)도 **표준이 정한 것**이라
어느 기관 API 든 같다. 한 번 배우면 계속 쓴다.

## ★ JWT 는 암호화가 아니다

```
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJvdXItc2kifQ . 3d9843f64521...
└──── (1) 헤더 ────┘   └──── (2) 내용 ────┘   └── (3) 서명 ──┘
      Base64                 Base64              열쇠가 있어야 만듦
```

(1)(2)는 **비밀키 없이 누구나 읽는다.** `/interop/token-peek` 로 직접 확인했다.
🚨 **JWT 안에 비밀번호·주민번호·개인정보를 넣지 않는다.**

공격자가 **할 수 있는 것**: 열어보기 / 내용 바꾸기 / 다시 인코딩
공격자가 **못 하는 것**: 바꾼 내용에 맞는 **서명 만들기** <- 여기서 막힌다
→ 지난 시간 HMAC 과 완전히 같은 구조. JWT 는 그걸 포장한 형식일 뿐이다.

## Base64 두 종류

```java
Base64.getDecoder()      // 표준.  + 와 / 를 쓴다
Base64.getUrlDecoder()   // URL-safe. + -> - , / -> _ , 끝의 = 패딩 없음   <- JWT 는 이것
```

★ 표준 디코더로도 **대부분 성공한다.** `+` `/` 가 될 바이트가 안 나오면 결과가 같으니까.
  → **"토큰 디코딩이 가끔 실패해요"** 의 정체. 열에 아홉은 되는데 어쩌다 터진다.
  인코딩할 때는 `getUrlEncoder().withoutPadding()` 을 쓴다.

## 실습 창구

| 주소 | 무엇을 보나 | 결과 |
|---|---|---|
| `/interop/token` | 발급 응답 원문 | `access_token` `expires_in:300` |
| `/interop/token-peek` | **비밀키 없이 내용 읽기** | 헤더·내용이 JSON 으로 다 보임 |
| `/interop/jwt-call?alg=HS256` | 토큰으로 API 호출 | 200 |
| `/interop/jwt-call?alg=RS256` | 같은 것, 다른 알고리즘 | 200 (**결과가 똑같다**) |
| `/interop/jwt-tampered` | scope 를 admin.all 로 올림 | **401 서명 불일치** |
| `/interop/jwt-expired` | exp 만 과거인 토큰 | **401 토큰 만료** |

★ 서명 검증과 만료 검증은 **다른 검사**다. `jwt-expired` 는 서명이 완전히 정상이다.

## ★ HS256 vs RS256 — 오늘의 결승선

```
HS256                          RS256
[상대] 비밀키 1개                [상대] 개인키 (밖으로 안 나감)
   v 서명                          v 서명
 토큰                            토큰
   |  검증하려면                   |  검증하려면
   v  같은 비밀키가 필요            v  공개키만 있으면 됨
[우리] 비밀키 복사본 🔑           [우리] 공개키 📄
```

| | HS256 | RS256 |
|---|---|---|
| 열쇠 | 1개 (공유) | 2개 |
| 상대에게 주는 것 | 🔴 **비밀키 원본** | 🟢 공개키 |
| 기관 10곳이면 | 🔴 비밀키가 10곳에 복사됨 | 🟢 문제없음 |
| 한 곳이 털리면 | 🔴 **전부 재발급** | 🟢 아무 일 없음 |
| 상대가 토큰을 위조하면 | 🔴 **구분 불가** | 🟢 불가능 |
| 부인방지 | ❌ | ✅ |

★ **한 줄 규칙: 만드는 쪽과 검증하는 쪽이 같은 조직이면 HS256, 다른 조직이면 RS256.**
  **공공 연계는 항상 다른 조직이다 → RS256.**

🚨 **쓰는 쪽에서는 차이가 안 보인다.** 코드도 결과도 같다. 잘못 골라도 테스트가 다 통과한다.
   차이는 동작이 아니라 **'누가 무엇을 갖고 있어야 하는가'** 에서 난다.

## JWKS — 공개키를 HTTP 로 나눠주는 창구

```powershell
docker compose exec boot curl --cert /app/certs/client.crt --key /app/certs/client.key --cacert /app/certs/ca.crt https://partner-gw:8443/oauth2/jwks
```

★ **이 창구에는 인증이 없다.** 공개키는 공개해도 되는 값이기 때문이다.
  **HS256 이었다면 이런 창구를 만들 수 없다** — 비밀키를 HTTP 로 뿌리는 셈이니까.
  이 창구가 존재할 수 있다는 것 자체가 비대칭의 값어치다.

실무 신호: 상대 기관이 **"JWKS URL 드릴게요"** 하면 = "RS256 쓰고 공개키는 여기서 받아가세요".
`kid`(key id)로 어느 열쇠인지 구분하므로, 기관이 열쇠를 바꿔도 우리는 코드를 안 고친다.

## 🚨 alg 혼동 공격

```python
if alg == "RS256":   claims = pyjwt.decode(token, RS_PUBLIC_PEM, algorithms=["RS256"])
elif alg == "HS256": claims = pyjwt.decode(token, JWT_HS_SECRET, algorithms=["HS256"])
else:                raise HTTPException(401, f"허용하지 않는 알고리즘: {alg}")
```

`alg` 는 **토큰 안에 적혀 있다 = 공격자가 고칠 수 있는 값이다.**
그대로 믿으면 `alg: none` 으로 바꿔 서명을 지우는 공격이 통한다.
→ 서버가 **받아들일 알고리즘을 명시**해야 한다(`algorithms=[...]`).

★ 회사 소스에서 JWT 검증 코드를 보면 `algorithms` / `setAllowedAlgorithms` 가 있는지 확인할 것.
  없으면 취약점이다. 시큐어코딩 점검 항목(Phase 4.5 에서 재회).

## 지금까지 쌓은 층 (Phase 4 전체)

```
토큰(JWT)     - 지금 이 요청이 허용된 업무인가        <- 실습 5
HMAC          - 전문이 안 바뀌었나                   <- 실습 4
mTLS(RSA)     - 접속하는 게 누구인가                 <- 실습 3
TLS 암호화     - 엿듣기 차단                        <- Phase 2
```

---

## 실습 6 — 장애 3종 (타임아웃 · 재시도 · 멱등성)

연계 상대는 언젠가 반드시 셋 중 하나가 된다.

```
① 느려진다        죽지는 않았는데 응답이 안 온다   <- 가장 흔하고 가장 위험
② 가끔 실패한다    열 번에 한 번 500 이 난다
③ 응답을 못 받았다  처리는 됐는데 우리가 결과를 모른다  <- 중복의 씨앗
```

### 파일 지도

```
partner/app.py                        상대 기관에 장애 재현 창구 3개 추가
  GET  /openapi/slow?sec=N             느린 상대
  GET  /openapi/flaky                  앞의 2번은 500, 그 뒤 성공
  POST /openapi/payment                거래 등록 (Idempotency-Key 인식)
  GET  /openapi/payments               ★ 상대 원장 - 진실은 여기 있다

src/main/java/com/study/interop/ResilientClient.java    ★ 오늘의 전부
src/main/resources/application.properties
  server.tomcat.threads.max=5          운영 기본은 200. 고갈을 재현하려고 줄인 값
```

### 1막 — 타임아웃

```
http://localhost:9600/interop/slow?sec=2     -> 성공 (2757ms)
http://localhost:9600/interop/slow?sec=8     -> 타임아웃 (5004ms)
```

값의 흐름:

```
InteropApplication.java  .setReadTimeout(Duration.ofSeconds(5))
      -> RestTemplate 안의 ClientHttpRequestFactory
      -> ResilientClient.callSlow()  rt.getForObject(url, ...)
      -> 소켓에서 5000ms 동안 아무것도 안 읽히면
      -> java.net.SocketTimeoutException: Read timed out
      -> 스프링이 감싼다 -> ResourceAccessException   <- 우리가 catch 하는 것
```

★ 타임아웃에는 HTTP 상태코드가 없다. 상대가 500 을 준 게 아니라 아무 말도 안 한 것이다.

| 잡히는 예외 | 무슨 일이 있었나 | 상대 로그에 | 재시도 |
|---|---|---|---|
| HttpServerErrorException 500 | 상대가 받아서 처리하다 터졌다 | 있다 | 조건부 |
| ResourceAccessException | 상대가 말이 없다 | 있을 수도, 없을 수도 | 위험 |

★ ResourceAccessException 이 무서운 이유: 상대가 처리까지 다 했는데 응답만 못 온 것일 수 있다.
  우리는 실패로 알고 상대는 성공으로 안다. 이것이 3막(멱등성)의 씨앗이다.

★ 타임아웃은 두 종류다. 장애 보고서에 "타임아웃 5초" 라고만 적혀 있으면 반쪽이다.
    connectTimeout : 전화를 안 받는다 (방화벽, 서버 다운)
    readTimeout    : 전화는 받았는데 말을 안 한다 (DB 잠금, 무한루프)
  흔한 사고 조합 = 연결 3초 / 읽기 무제한.

★ new RestTemplate() 의 기본 타임아웃은 0 이고, 0 은 "즉시 포기" 가 아니라 "무제한" 이다.

### 2막 — 재시도

```
http://localhost:9600/interop/reset
http://localhost:9600/interop/flaky-once     -> 500 실패
http://localhost:9600/interop/reset
http://localhost:9600/interop/flaky-retry    -> 3회차에 성공
```

```
1회차 : 500 -> 200ms 뒤 재시도
2회차 : 500 -> 400ms 뒤 재시도
3회차 : 성공
```

`delayMs *= 2;` 한 줄이 지수 백오프(exponential backoff)다. 간격을 안 늘리면:

```
상대가 과부하로 500 -> 우리가 0.2초마다 두들김 -> 상대는 더 과부하
-> 더 많은 500 -> 더 많은 재시도   ... 재시도가 장애를 키운다 (retry storm)
```

실무에는 여기에 지터(jitter, 대기시간에 난수를 섞음)를 더한다. 서버 100대가 동시에
실패하면 100대가 정확히 같은 순간 재시도해서 또 무너뜨리기 때문.

★ 무엇을 재시도할 것인가 — 오늘의 핵심 규칙

```
5xx      상대 서버 잘못   다시 보내면 될 수도 있다    재시도 O
타임아웃  상대가 느림      다시 보내면 될 수도 있다    재시도 O (조건부)
4xx      우리 요청 잘못   백 번 보내도 똑같다        재시도 X
```

회사 소스 점검: catch 가 갈라져 있지 않고 `catch (Exception e)` 로 뭉뚱그려
재시도하면 의심할 것. 4xx 재시도는 상대 서버만 두들기고 결과는 같으며,
공공기관 API 는 호출량 제한이 있어 심하면 우리 IP 가 차단된다.

### 3막 — 재시도가 위험해지는 순간 (멱등성)

```
/interop/reset
/interop/pay?times=3&key=off
/interop/payments        -> 실제처리건수 : 3     결제가 3번 됐다
/interop/reset
/interop/pay?times=3&key=on
/interop/payments        -> 실제처리건수 : 1     나머지 2번은 첫 결과를 돌려받음
```

★ 코드에서 다른 건 딱 한 곳, UUID 를 만드는 '위치' 다.

```java
public Map<String, Object> pay(...) {
    String idemKey = UUID.randomUUID().toString();   // ★ 루프 밖
    for (int i = 1; i <= times; i++) {
        headers.set("Idempotency-Key", idemKey);     // 3번 다 같은 값
```

```
키를 만드는 위치 = 멱등성의 단위
  for 루프 밖   ->  "업무 1건" 이 단위    맞다
  for 루프 안   ->  "호출 1번" 이 단위    키가 없는 것과 같다
```

★ 실무에서 가장 흔한 구현 실수가 정확히 이것이다. 코드는 있는데 위치가 틀린 것.
  Idempotency-Key 를 세팅하는 코드를 보면 그 키를 어디서 만들었는지 위로 거슬러 올라갈 것.

★ 키 이름은 우리가 발명하는 게 아니라 규격서가 정한다.

```
Idempotency-Key          상용 API (결제사, 클라우드)
거래고유번호              금융 연계
전문관리번호              공공 표준 연계 전문
요청일련번호 + 기관코드    기관별 자체 규격
```

규격서에 이런 항목이 없는데 POST 로 등록/결제를 한다면 그 연계는 재시도가 위험하다.
설계 단계에서 물어봐야 할 항목이다.

★ HTTP 메서드 자체에 이미 답이 있다.

```
GET     조회      몇 번 해도 같음         원래 멱등. 마음껏 재시도
PUT     통째 교체  몇 번 해도 같음         원래 멱등
DELETE  삭제      두 번째는 "이미 없음"    원래 멱등
POST    등록      할 때마다 새로 생김     <- 여기만 문제
```

회사 소스에서 재시도 로직을 보면 볼 곳은 하나다. 그 재시도가 감싼 게 POST 인가?
POST 면 멱등성 키가 붙어 있는지 확인, GET 이면 넘어가도 된다.

★ nonce 와 멱등성 키 (실습 4 에서 이어짐)

| | 누가 다시 보냈나 | 어떻게 대응 |
|---|---|---|
| nonce | 공격자가 몰래 복사 | 거부한다 |
| 멱등성 키 | 우리가 못 받아서 재시도 | 첫 결과를 돌려준다 |

app.py 의 IDEM_STORE 는 학습용 메모리 딕셔너리다. 실무에서는 Redis 나 DB 테이블이어야 한다.
재기동해도 남아야 하기 때문. (Phase 2 에서 세션을 Redis 로 뺀 것과 같은 이유)

### 4막 — 스레드 풀 고갈

PowerShell 창 2개.

```powershell
# 1번 창 : 느린 호출 5개로 스레드를 전부 점유
1..5 | ForEach-Object { Start-Job { curl.exe -s -m 60 "http://localhost:9600/interop/slow-notimeout?sec=25" } }

# 2번 창 : 3초 안에, 연계와 아무 상관 없는 창구를 호출
Measure-Command { curl.exe -s -m 15 "http://localhost:9600/interop/ping" }

# 정리
Get-Job | Remove-Job -Force
```

/ping 은 외부 호출이 하나도 없는 창구인데 응답이 안 온다. (실측: 15초 타임아웃, 종료코드 28)

```
톰캣 스레드 5개 (운영은 200개)
  1~5 -> slow-notimeout 에 전부 잠김
  /ping, /login, /main  ->  처리할 스레드가 없어 대기
```

★ 연계 상대 한 곳이 느려졌을 뿐인데 우리 서비스 전체가 멎는다.
  장애 보고서에 "OO기관 연계 서버 응답 지연으로 포털 전체 서비스 불가" 로 적히는 사건의 정체.
  이름은 스레드 풀 고갈(thread pool exhaustion).

★ 스레드를 늘리는 건 대개 해결이 아니라 '터지는 시각을 늦추는 것' 이다.
  진짜 원인은 대부분 '돌아오지 않는 외부 호출' 이다.

다음 단계 장치는 차단기(Circuit Breaker) — 상대가 계속 실패하면 아예 호출을 멈추고
즉시 실패시킨다(Resilience4j 등). 단, 타임아웃이 없으면 차단기도 소용없다. 순서가 있다.

### GS 인증 신뢰성 시험과의 대응

| GS 신뢰성 부특성 | 시험원이 하는 것 | 오늘 대비한 것 |
|---|---|---|
| 결함허용성 | 연동 서버를 죽이고 화면을 눌러봄 | 타임아웃 -> 우리는 안 죽음 |
| 회복성 | 죽였던 서버를 살림 | 재시도 -> 저절로 복구 |
| 성숙도 | 같은 조작을 반복 (저장 버튼 연타) | 멱등성 -> 중복 안 생김 |

### Phase 4 누적 층

```
장애 3종      - 상대가 정상이 아닐 때 우리가 버티나   <- 실습 6
토큰(JWT)     - 지금 이 요청이 허용된 업무인가        <- 실습 5
HMAC          - 전문이 안 바뀌었나                   <- 실습 4
mTLS(RSA)     - 접속하는 게 누구인가                 <- 실습 3
TLS 암호화     - 엿듣기 차단                        <- Phase 2
```

---

## 실습 7 — 파일 배치 (SFTP)

연계 3형태 중 **파일**이다. API 를 여섯 번 배웠지만, 공공 연계의 절반 이상은 아직 여기 있다.

```
왜 아직도 파일인가
  망분리    업무망과 인터넷망이 끊겨 있어 실시간 호출 자체가 불가능한 곳이 많다
  대량      수십만 건을 API 로 한 건씩 부르면 밤이 새도 안 끝난다
  오래됨    20년 전에 만든 연계는 그때 방식 그대로 돌고 있다
```

### 누가 누구인가

```
partner        상대 기관 배치.  23:00 에 파일을 만들어 /data/outbox 에 떨군다
   │  (같은 디스크 = sftpdata 볼륨)
partner-sftp   상대 기관 SFTP 서버.  같은 디스크를 /home/eaiuser/outbox 로 물고 있다
   │  22/TCP
boot           우리.  06:00 에 가져가서 뜯고 우리 원장에 넣는다
```

★ 파일을 **만드는 놈**과 **내보내는 놈**이 다르다. 그래서 우리가 가져가는 순간
상대가 아직 쓰는 중일 수 있다. 오늘의 함정 1이 여기서 나온다.

### 파일 지도

```
docker-compose.yml
  partner-sftp:                     ★ 오늘 추가된 상대 서버 (atmoz/sftp)
  volumes: sftpdata                 만드는 쪽과 내보내는 쪽이 공유하는 디스크

partner/app.py
  POST /openapi/batch/seed          상대 야간 배치를 '지금' 돌린다
  GET  /openapi/batch/outbox        상대 디스크를 들여다본다 (현실엔 없는 창구)

src/main/java/com/study/interop/SftpBatchClient.java     ★ 오늘의 전부
src/main/resources/application.properties   sftp.host / port / user / password
연계규격서.md  6장                  ★ 전문 레이아웃은 규격서가 정한다
```

### 값의 흐름 — 비밀번호는 어디서 오나

```
.env                     SFTP_PASSWORD=...
  -> docker-compose.yml  boot.environment.SFTP_PASSWORD
  -> application.properties  sftp.password=${SFTP_PASSWORD:}
  -> SftpBatchClient  @Value("${sftp.password}")
  -> session.setPassword()
```

소스 어디에도 비밀번호가 없다. `.env` 는 `.gitignore` 에 있다.

### 명령

```
0) 상대가 파일을 떨군다
   http://localhost:9600/interop/sftp/seed
   http://localhost:9600/interop/sftp/outbox

1) SFTP 로 목록만 본다
   http://localhost:9600/interop/sftp/list
```

### 1막 — 함정 ① 덜 쓰인 파일

```
http://localhost:9600/interop/sftp/fetch?safe=on     -> 0001, 0002 만 받음
http://localhost:9600/interop/sftp/fetch?safe=off    -> 0003 까지 받음
```

`0003` 은 `.done` 이 없다. 상대 배치가 아직 쓰는 중이다.

★ 이 사고는 **다운로드가 성공한다.** 파일도 멀쩡히 존재한다.
  깨진 건 다음날 민원이 들어와서야 안다. 그래서 무섭다.

해결은 프로토콜이 아니라 **약속**으로 한다.

```
(a) 완료표시 파일     data.dat 를 다 쓴 뒤 data.dat.done 을 만든다   <- 이 실습
(b) 임시이름 후 rename  data.tmp 로 쓰고 다 쓰면 data.dat 로 이름만 바꾼다
                      rename 은 같은 디스크 안에서 순간이라 중간 상태가 없다
```

★ 규격서에 이 약속이 없으면 그 연계는 언젠가 반드시 깨진 파일을 읽는다.
  설계 회의에서 물어야 할 항목이다. (실습 6 의 "멱등성 키 항목이 규격서에 있나" 와 같은 자리)

### 2막 — 함정 ② 인코딩

```
http://localhost:9600/interop/sftp/parse?file=SUCH-20260908-0001.dat&charset=euc-kr
http://localhost:9600/interop/sftp/parse?file=SUCH-20260908-0001.dat&charset=utf-8
```

```
euc-kr  성명 : 김유신
utf-8   성명 : ������
```

공공 전문은 아직도 EUC-KR(=CP949) 이 흔하다. 규격서 6.4 에 적혀 있고,
안 읽으면 한글이 깨진다. 여기까지는 눈에 보이니 차라리 낫다. 진짜는 다음이다.

### 3막 — 함정 ③ 고정길이는 '바이트'로 잘라야 한다

```
http://localhost:9600/interop/sftp/parse?file=SUCH-20260908-0001.dat&mode=byte
http://localhost:9600/interop/sftp/parse?file=SUCH-20260908-0001.dat&mode=char
```

```
mode=byte   기관코드 B1234567 | 성명 김유신              | 생년월일 19850312 | 금액 0001250000 | 구분 1
mode=char   기관코드 B1234567 | 성명 김유신          198 | 생년월일 50312000 | 금액 12500001   | 구분 (범위밖)
```

★ 규격서의 "성명 20" 은 **20글자가 아니라 20바이트**다.
  EUC-KR 에서 한글 1글자 = 2바이트이므로 `"김유신"` 은 6바이트 = 3글자.
  바이트로 채운 자리를 글자로 세면 그 뒤 항목이 **전부 밀린다.**

```java
// 맞음 — 바이트 위치로 자르고 '그 다음에' 문자로 바꾼다
new String(line, 8, 20, cs)

// 틀림 — 문자로 바꾼 뒤 자른다. 회사 소스에서 제일 흔히 보이는 모양
new String(line, cs).substring(8, 28)
```

★ 무서운 점: **금액이 12500001 로 나와도 프로그램은 안 죽는다.**
  숫자로 보이고, 파싱도 되고, DB 에도 들어간다. 사람이 안 보면 아무도 모른다.

★ 두 번째 방어선 = **레코드 길이 검증**.

```
http://localhost:9600/interop/sftp/parse?file=SUCH-20260908-0003.dat
-> 불량레코드 : 레코드 길이 22바이트 (규격 47바이트) -> 잘린 파일이다
```

고정길이 전문은 길이가 정해져 있으므로 **길이만 재도 잘린 파일을 잡는다.**
`.done` 마커를 못 쓰는 상황에서도 이건 할 수 있다.

### 4막 — 함정 ④ 중복 적재 (실습 6 의 파일판)

배치 → 상대가 같은 파일 재전송 → 배치. 두 번 돌린다.

```
http://localhost:9600/interop/sftp/reset
http://localhost:9600/interop/sftp/seed
http://localhost:9600/interop/sftp/run?history=off
http://localhost:9600/interop/sftp/seed          <- 상대가 같은 파일을 다시 올렸다
http://localhost:9600/interop/sftp/run?history=off
http://localhost:9600/interop/sftp/ledger        -> 적재건수 10   ★ 수당이 두 번 나갔다
```

```
같은 순서로 history=on 이면                       -> 적재건수 5
배치로그 : SUCH-20260908-0001.dat : 건너뜀 (이미 처리한 파일)
```

★ 실습 6 과 완전히 같은 그림이다. 이름만 바뀐다.

| | 무엇을 보고 판단하나 | 어디에 저장하나 |
|---|---|---|
| HTTP 연계 | Idempotency-Key | Redis / DB |
| 파일 배치 | 처리한 파일명(+크기, 해시) | 배치이력 테이블 |

★ 그리고 처리한 파일은 **지우지 않고 옮긴다.**

```
/outbox/SUCH-20260908-0001.dat  ->  /outbox/archive/SUCH-20260908-0001.dat
```

- 상대가 "그 날 파일 다시 주세요" 하는 날이 반드시 온다
- archive 로 옮기면 목록에서 사라져서 다음 배치가 다시 집지 않는다
- ★ **이동에 실패하면 다음 배치가 같은 파일을 또 집는다.** 조용한 사고라 로그가 필수다

### 보안 — 오늘의 '끄면 안 되는 것'

```java
cfg.put("StrictHostKeyChecking", "no");   // ★ 학습용. 운영에서 이러면 안 된다
```

`no` 로 두면 상대 서버가 바뀌어도 그냥 붙는다 = **중간자 공격을 못 잡는다.**
TLS 에서 `curl -k` / 인증서 검증 끄기 와 정확히 같은 죄다.

```
정답 :  ssh-keyscan -p 22 상대호스트 >> known_hosts
        jsch.setKnownHosts("/app/certs/known_hosts");
        StrictHostKeyChecking = yes
```

실습 코드는 대신 접속할 때마다 **서버 지문(fingerprint)** 을 찍는다.
운영에서는 그 지문을 상대 기관 담당자와 **전화로 대조**한 뒤 고정한다.

```
비밀번호 vs 키 인증
  비밀번호  공문으로 받는다. 주기적 변경 요구가 오면 배치가 그날 밤 죽는다
  키 인증   우리 공개키를 상대 authorized_keys 에 넣는다. 만료가 없어 운영이 편하다
           ★ 개인키는 절대 상대에게 보내지 않는다 (.crt 는 보내도 .key 는 안 보낸다 와 같은 규칙)
```

### 라이브러리 함정 — `Algorithm negotiation fail`

```xml
<groupId>com.github.mwiede</groupId>   <!-- 유지보수판 -->
<artifactId>jsch</artifactId>
```

원조 `com.jcraft:jsch` 는 **0.1.55(2018)** 에서 멈췄다.
요즘 OpenSSH 서버는 옛 알고리즘(ssh-rsa 등)을 더는 안 받아준다.

```
com.jcraft.jsch.JSchException: Algorithm negotiation fail
```

오래된 공공 소스에서 이 오류를 만나면 원인은 방화벽도 계정도 아니고
**라이브러리가 늙은 것**이다. `pom.xml` 의 jsch 버전부터 볼 것.

### 회사 소스에서 볼 5곳

```java
new JSch(); ... session.connect();          // ① 타임아웃 인자가 비어 있나 (배치가 밤새 매달린다)
StrictHostKeyChecking = "no"                // ② 호스트키 검증을 껐나
new String(bytes).substring(a, b)           // ③ 고정길이를 글자로 자르나
new String(bytes)                           // ④ 문자셋을 안 줬나 (서버 기본값에 끌려간다)
ls() 결과를 그대로 for 로 돌리나            // ⑤ .done 확인 없이 다 집어가나
```

### 오늘의 정리

```
① 덜 쓰인 파일   .done 마커 / .tmp 후 rename  — 규격서에 없으면 물어봐야 한다
② 인코딩        EUC-KR. new String 에 문자셋을 반드시 준다
③ 바이트 vs 글자  고정길이는 byte 로 자른다. 안 죽고 값만 밀려서 무섭다
④ 중복 적재      처리이력 = 배치판 멱등성. 처리 후 archive 로 '이동'
```

### Phase 4 누적 층

```
파일 배치      - 실시간이 아닐 때 어떻게 주고받나   <- 실습 7
장애 3종       - 상대가 정상이 아닐 때 버티나       <- 실습 6
토큰(JWT)      - 이 요청이 허용된 업무인가         <- 실습 5
HMAC          - 전문이 안 바뀌었나                <- 실습 4
mTLS(RSA)     - 접속하는 게 누구인가              <- 실습 3
TLS 암호화     - 엿듣기 차단                      <- Phase 2
```

---

## 실습 8 — 권한 승계 (Document-level Security)

인증(누구냐)이 아니라 **인가(뭘 볼 수 있냐)** 다. 실습 1~5 를 완벽하게 해도 이건 하나도 해결되지 않는다.

```
원본 파일서버                 검색 시스템
─────────────                ──────────
인사팀 폴더    ──── 수집 ────▶  전부 색인
복지팀 폴더    ──── 수집 ────▶  전부 색인
감사팀 폴더    ──── 수집 ────▶  전부 색인
                                  │
                                  └─▶ 검색창에서는 누구나 다 나온다   ★
```

원본 폴더에는 권한이 걸려 있었다. **수집해서 색인에 넣는 순간 그 권한이 사라진다.**
검색 시스템이 그대로 유출 통로가 된다.

### 권한은 두 층이다

```
문서 단위 권한   누가 이 문서를 볼 수 있나              <- 실습 8
OS·계정 권한     배치 프로세스가 이 폴더를 읽을 수 있나    <- 실습 7 에서 chmod 777 로 덮어둔 것
```

### 그리고 '권한' 이라는 말이 세 군데 쓰인다

```
TLS 암호화               엿듣기 차단                           Phase 2
API Key / 토큰 / mTLS     "우리 서버가 붙어도 되는가"            실습 1~5
문서 ACL                 "이 최종 사용자가 이 문서를 봐도 되는가"  실습 8
```

★ 위의 것을 아무리 완벽하게 해도 아래 것은 해결되지 않는다. 층이 다르다.

### 파일 지도

```
partner/app.py                        기관 전산실 두 개를 흉내낸다
  GET  /openapi/docs/list               문서 + allow/deny  (수집기가 부르는 창구)
  GET  /openapi/docs/can-read           "얘 이거 봐도 돼?"  (late binding 창구)
  POST /openapi/docs/revoke             관리자가 권한 회수
  GET  /openapi/directory/user          인사시스템 - 사람 -> 그룹
  _expand()                             ★ 중첩 그룹을 위로 펼치는 함수

src/main/java/com/study/interop/DocSearchClient.java    ★ 오늘의 전부
  :162  whoami()        누구냐 (검색 1회당 1번)
  :180  권한 필터        여기가 '권한 승계'
```

### 등장인물

```
hong  홍길동   인사팀        kim  김철수   복지팀
lee   이영희   감사팀        park 박민수   복지팀+인사팀(겸직)

그룹 나무   인사팀 ⊂ 경영지원본부    복지팀 ⊂ 복지본부    감사팀 ⊂ 감사관실
            + 모두가 '전직원' 에 속한다 (윈도우의 Everyone 자리)
```

### 명령

```
http://localhost:9600/interop/acl/seed
http://localhost:9600/interop/acl/whoami?user=hong
http://localhost:9600/interop/acl/index?acl=on
```

`whoami` 가 오늘의 출발점이다.

```
직접소속  [인사팀]
실효그룹  [인사팀, 경영지원본부, 전직원]
```

★ 권한 필터가 비교하는 건 **직접소속이 아니라 실효그룹**이다.

### 1막 — 필터가 없을 때

```
/interop/acl/search?user=kim&mode=none     -> 6건
/interop/acl/search?user=kim&mode=early    -> 1건
```

복지팀 김철수에게 인사팀 급여파일과 징계 회의록이 그대로 나왔다.

```
수집 단계   관리자 권한으로 전부 읽는다        <- 여기까지는 그래야 한다
색인 단계   본문을 색인에 넣는다              <- ★ 이 순간 원본 권한이 사라진다
검색 단계   색인에 권한이 없으니 다 보인다
```

★ **"수집은 전권으로, 검색은 개인 권한으로"** — 이 둘이 갈라지는 지점을 설계하는 게 권한 승계다.

### 2막 — 가장 흔한 구현 버그 (deny 를 안 본다)

```
/interop/acl/search?user=hong&mode=allow-only   -> 5건   ★ 징계위원회 회의록이 샌다
/interop/acl/search?user=hong&mode=early        -> 4건
```

```
DOC-006  allow = [경영지원본부]    본부 사람은 봐도 된다
         deny  = [인사팀]         단, 인사팀은 빼라 (당사자 부서)
```

홍길동은 둘 다 걸린다. 그래서 **보는 순서가 결과를 뒤집는다.**

```java
// 맞음 — DocSearchClient.java:212
if (겹치나(내그룹, deny))        허용 = false;   // ★ deny 를 먼저
else if (겹치나(내그룹, allow))  허용 = true;

// 틀림 — 회사 소스에서 보게 될 모양
if (겹치나(내그룹, allow))       허용 = true;    // allow 가 먼저면 deny 는 영영 안 본다
else if (겹치나(내그룹, deny))   허용 = false;
```

★ **deny 가 allow 를 이긴다.** 윈도우 파일서버, AD, S3, DB 권한 전부 같은 규칙이다.

★ 틀린 쪽도 **테스트를 통과한다.** 대부분 문서는 deny 가 비어 있기 때문이다.
  deny 가 걸린 소수에서만 틀리고, 그 소수가 하필 제일 민감한 문서다.

### 3막 — 반대편 사고 (중첩을 안 펼친다)

```
/interop/acl/search?user=hong&mode=early              -> 4건  [경영지원본부, 인사팀, 전직원]
/interop/acl/search?user=hong&mode=early&nested=off   -> 2건  [인사팀]
```

사라진 것 : 경영지원본부_예산집행.xlsx, **기관운영계획.pdf(전직원 문서)**.
전 직원에게 공개한 문서가 전 직원에게 안 보인다.

```
과다 부여  ->  남의 문서가 보인다   ->  유출.  조용하다. 몇 달 뒤 감사에서 나온다
과소 부여  ->  내 문서가 안 보인다  ->  마비.  시끄럽다. 당일 전화가 온다
```

★ 마비가 먼저 고쳐지고, 그 과정에서 권한을 헐겁게 푸는 일이 생긴다.
  "일단 다 보이게 해주세요" 가 나오는 지점. 여기서 물러서면 1막으로 돌아간다.

중첩 주의 2가지 : **끝까지 올라갈 것**(한 단계만 올라가면 아래 팀이 전직원 문서를 못 본다),
**순환을 조심할 것**(A⊂B, B⊂A 설정이 실제로 있다. 방문 표시 없이 재귀하면 무한루프).

### 4막 — early 냐 late 냐

```
/interop/acl/revoke?doc=DOC-002&group=인사팀    원본에서 권한 회수
/interop/acl/search?user=hong&mode=early        -> 급여파일이 ★아직 보인다  (2ms)
/interop/acl/search?user=hong&mode=late         -> 즉시 막힌다              (19ms)
/interop/acl/calls                              -> 원본에 6번 물어봤다
```

★ early 가 낡는 것은 **버그가 아니라 설계다.** 빠름을 얻으려고 최신성을 판 것.
  버그는 이 사실을 모르고 쓰는 것이지, 낡는다는 사실 자체가 아니다.

그래서 현장 질문은 "왜 안 바뀌죠?" 가 아니라 **"몇 분까지 낡아도 됩니까?"** 가 된다.
그리고 그건 개발자가 아니라 **기관이 정해줘야 하는 값**이다.

| | early binding | late binding |
|---|---|---|
| 언제 권한을 보나 | 색인할 때 떠둔 사본 | 검색할 때 원본에 직접 |
| 속도 | 빠르다 | 느리다 (결과 건수만큼 호출) |
| 최신성 | ★ 낡는다 | 항상 최신 |
| 원본 장애 시 | 검색은 산다 | ★ 검색이 통째로 죽는다 |

★ late 의 진짜 대가는 속도가 아니라 **원본 시스템을 우리 검색의 필수 부품으로 만드는 것**이다.

```
검색 1회 = 원본 6번 호출.  사용자 100명이면 600번
-> 상대 TPS 제한에 걸린다            (실습 6)
-> 원본이 느려지면 검색창 전체가 멎는다  (실습 6 스레드 풀 고갈)
```

**실무 정답은 둘 중 하나가 아니라 섞는 것이다.**

```
1단계  early 로 넓게 거른다        수천 건 -> 20건
2단계  화면에 보여줄 20건만 late   원본에 20번만 물어본다
```

여기에 재색인 전략을 얹는다.

```
전량 재색인   매일 밤
권한만 갱신   본문은 그대로 두고 allow/deny 만 다시 받는다 (훨씬 가볍다)
이벤트 즉시   퇴사·부서이동·권한회수는 그 문서만 즉시
```

★ **퇴사자 계정이 검색에서 살아있는 기간** = 위험 기간. 보안 감사에서 실제로 묻는 항목이다.

### 5막 — 원본이 권한을 안 줄 때

```
/interop/acl/index?acl=off
/interop/acl/search?user=hong&mode=early    -> 0건
```

0건은 상대가 준 게 아니라 **우리 필터가 만든 숫자**다. 수집 계정은 전권이라 상대는 전부 준다.
못 받는 게 아니라 **권한만 못 받는** 것이다. 그러면 선택지가 둘뿐이다.

```
필터를 끈다   ->  전부 보인다        유출
필터를 켠다   ->  아무것도 안 보인다   무용지물
```

★ **이건 코드로 못 고친다.** 없는 정보는 만들 수 없다. 설계 단계의 규격 협의로 푸는 문제다.

| 상대가 주는 것 | 우리가 할 수 있는 것 |
|---|---|
| 본문 + 권한(ACL) | early binding 가능. 제일 좋다 |
| 본문 + 권한조회 창구 | late binding 가능 |
| 본문만 | ★ 아무것도 못 한다 |

### 두 가지 모델 — 원본 승계 vs 제품 자체 관리

```
① 원본 승계    원본 시스템의 권한을 그대로 물려받는다        <- 이 실습
② 자체 관리    제품 안에 조직도와 권한을 따로 둔다
               (설치할 때 그 기관 조직도를 지정하는 방식)
```

②에서도 오늘 배운 건 그대로 쓰인다. **자리만 바뀐다.**

```
인사시스템(그룹 조회)   ->  제품 안의 조직도
원본 문서의 allow/deny  ->  제품에서 지정한 폴더·문서 권한
색인의 권한 사본        ->  그대로
```

②의 함정 4가지

1. **조직도가 두 벌이 된다.** 기관 인사시스템에 진짜가 있고 제품 안에 사본이 있다.
   인사발령이 나면 진짜만 바뀐다. ★ 4막의 '낡음' 과 같은 구조다.
2. **제품 권한이 원본보다 넓어질 수 있다.** ②는 원본을 안 보므로 스스로 못 잡는다.
   → 물어볼 한 문장 : **"우리 제품 권한이 원본보다 넓어질 수 있습니까?"**
   넓어질 수 없게 하려면 원본 권한도 읽어서 **교집합**만 허용해야 한다(①②혼합).
3. **권한 단위가 폴더인데 원본은 문서다.** 폴더 안의 예외 문서 하나가 샌다.
   이 실습의 DOC-006 이 정확히 그 예다. ②에서 가장 흔한 실제 사고.
4. **책임 소재가 바뀐다.** ①은 문서 소유 부서가, ②는 **제품을 설정한 사람**이 권한을 정한 것이 된다.
   그래서 ②는 권한 변경 이력이 남아야 하고, 그게 보안 감사 항목이 된다.

### 수집 대상 시스템마다 물어볼 것

그룹웨어·문서관리·파일서버·게시판이면 **각각** 물어야 한다.

```
① 권한 정보를 같이 주나?         안 주면 여기서 이미 막힌다
② 권한의 단위가 무엇인가?        사람 / 부서 / 그룹 / 직급
③ 그룹이 중첩되나? 몇 단계까지?   본부 > 실 > 팀 > 파트
④ deny(거부) 개념이 있나?        있으면 allow 보다 먼저 봐야 한다
⑤ 권한이 바뀌면 알려주나?        아니면 우리가 주기적으로 다시 읽어야 한다
⑥ 퇴사자는 언제 검색에서 사라지나?
```

★ ②가 제일 골치다. A는 부서코드, B는 AD 그룹, C는 사번 목록으로 권한을 건다.
  **셋을 하나의 기준으로 번역**해야 필터를 만들 수 있고, 그 번역표를 누가 관리하느냐가
  실제 프로젝트에서 제일 오래 끄는 논의다.

### 회사 소스에서 볼 4곳

```java
if (allow...) else if (deny...)        // ① deny 가 allow 보다 아래 있나
user.getGroups()                       // ② 직접 소속만 쓰나, 상위 그룹까지 펼치나
색인 필드에 acl_allow 가 있나           // ③ 없으면 early binding 자체가 불가능
색인 시각 / 재색인 주기                 // ④ 권한이 며칠까지 낡을 수 있나
```

### 오늘의 정리

```
① 수집은 전권, 검색은 개인 권한 — 이 둘이 갈라지는 곳이 권한 승계다
② deny 가 allow 를 이긴다. if 의 순서가 곧 보안이다
③ 과다 부여는 유출, 과소 부여는 마비. 둘 다 사고다
④ early 가 낡는 건 설계다. 질문은 "몇 분까지 낡아도 되나"
⑤ 권한을 안 주는 원본은 코드로 못 고친다. 규격 협의로 푼다
```

### Phase 4 누적 층 (완결)

```
권한 승계      - 이 사용자가 이 문서를 봐도 되나     <- 실습 8   ★ 인가
파일 배치      - 실시간이 아닐 때 어떻게 주고받나     <- 실습 7
장애 3종       - 상대가 정상이 아닐 때 버티나        <- 실습 6
토큰(JWT)      - 이 요청이 허용된 업무인가          <- 실습 5
HMAC          - 전문이 안 바뀌었나                 <- 실습 4   ↑ 여기까지 인증
mTLS(RSA)     - 접속하는 게 누구인가               <- 실습 3
TLS 암호화     - 엿듣기 차단                       <- Phase 2
```
