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
