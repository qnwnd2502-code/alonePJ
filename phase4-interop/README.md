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
