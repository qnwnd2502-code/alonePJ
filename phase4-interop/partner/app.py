# -*- coding: utf-8 -*-
"""
=========================================================================
 '상대 기관' 서버 흉내.  (우리가 연계해야 할 남의 시스템)

 회사에서는 이 서버 안을 절대 못 본다. 규격서 한 장만 받는다.
 학습할 때만 양쪽을 다 열어놓고 본다.

 파이썬으로 만든 이유: 상대 기관이 무슨 언어를 쓰든 상관없다는 걸 보이려고.
 연계는 HTTP 라는 공통 규격 위에서 일어난다. 상대가 자바든 파이썬이든
 닷넷이든, 우리 자바 코드는 똑같다.
=========================================================================
"""
import os
from fastapi import FastAPI, Request, HTTPException, Query, Header
from fastapi.responses import FileResponse

app = FastAPI(title="OO기관 연계 API")

# 상대 기관이 우리에게 발급해준 API Key.
# 실제로는 기관 담당자가 메일이나 공문으로 준다.
VALID_KEY = os.getenv("PARTNER_API_KEY", "eGov-DEMO-KEY-2026-abcdef123456")

FILE_DIR = "/app/files"

# 상대 기관 DB 의 COMTNFILEDETAIL 테이블이라고 치자.
# 파일 '경로'만 들어있고 파일 '실물'은 상대 서버 하드디스크에 있다.
FILE_TABLE = [
    {
        "atchFileId":    "FILE_000000000000123",
        "orignlFileNm":  "민원신청서.pdf",
        "streFileNm":    "a3f9c8e2-4b1d.txt",
        "fileStreCours": "/data/upload/2026/08/",
        "fileMg":        "2048",
        "creatDt":       "2026-08-20",
    },
    {
        "atchFileId":    "FILE_000000000000124",
        "orignlFileNm":  "처리결과통보서.hwp",
        "streFileNm":    "7c1e0b55-92aa.txt",
        "fileStreCours": "/data/upload/2026/08/",
        "fileMg":        "5120",
        "creatDt":       "2026-08-22",
    },
]


def check_key(key: str | None):
    """
    API Key 검사. 상대 기관 서버가 우리를 문 앞에서 확인하는 지점이다.
    401 = 너 누구냐(인증 실패).  403 = 누군진 알겠는데 권한 없다(인가 실패).
    """
    if not key:
        raise HTTPException(status_code=401, detail="serviceKey 가 없습니다")
    if key != VALID_KEY:
        raise HTTPException(status_code=401, detail="유효하지 않은 serviceKey 입니다")


@app.get("/openapi/file/list")
def file_list(
    request: Request,
    serviceKey: str | None = Query(default=None),        # 방식 1: URL 에 붙여 보냄
    authorization: str | None = Header(default=None),    # 방식 2: 헤더에 실어 보냄
):
    """
    파일 목록 조회. 상대 기관 DB 의 파일 테이블을 JSON 으로 내어준다.
    ★ 여기서 오는 건 '경로 문자열' 뿐이다. 파일 실물이 아니다.
    """
    # 헤더 방식이 있으면 그걸 쓰고, 없으면 URL 방식을 본다
    key = serviceKey
    if authorization and authorization.startswith("Bearer "):
        key = authorization[7:]

    # ★ 상대 기관 서버의 접속 로그. 이 한 줄이 오늘 실습의 핵심 교보재다.
    #   URL 에 키를 실어 보내면 여기 평문으로 남는다. https 를 써도 남는다.
    print(f"[ACCESS LOG] {request.method} {request.url}", flush=True)

    check_key(key)

    return {
        "resultCode": "00",
        "resultMsg":  "NORMAL SERVICE",
        "totalCount": len(FILE_TABLE),
        "items":      FILE_TABLE,
    }


@app.get("/openapi/file/download")
def file_download(
    request: Request,
    atchFileId: str,
    serviceKey: str | None = Query(default=None),
    authorization: str | None = Header(default=None),
):
    """
    파일 실물 내려받기.
    ★ 목록의 fileStreCours 경로는 '이 서버의' 경로다. 우리 서버에는 없다.
      파일을 실제로 가져오려면 이렇게 상대가 열어준 다운로드 창구를 써야 한다.
    """
    key = serviceKey
    if authorization and authorization.startswith("Bearer "):
        key = authorization[7:]

    print(f"[ACCESS LOG] {request.method} {request.url}", flush=True)
    check_key(key)

    row = next((f for f in FILE_TABLE if f["atchFileId"] == atchFileId), None)
    if row is None:
        raise HTTPException(status_code=404, detail="해당 파일이 없습니다")

    real_path = os.path.join(FILE_DIR, row["streFileNm"])
    if not os.path.exists(real_path):
        raise HTTPException(status_code=500, detail="서버에 파일이 없습니다")

    return FileResponse(
        real_path,
        media_type="application/octet-stream",
        filename=row["orignlFileNm"],
    )


@app.get("/openapi/echo")
def echo(request: Request):
    """
    ★ 학습 전용 창구. 실제 기관 API 에는 이런 게 없다.
      우리가 보낸 HTTP 요청이 '상대 서버에 어떻게 도착했는지' 를 그대로 되돌려준다.
      '헤더에 실었다' 가 눈에 안 보여서 만든 것이다.
    """
    return {
        "요청첫줄":   f"{request.method} {request.url.path}" + (f"?{request.url.query}" if request.url.query else ""),
        "쿼리스트링": dict(request.query_params) or "(없음)",
        "헤더전부":   dict(request.headers),
    }


@app.get("/openapi/health")
def health():
    """인증 없이 열어두는 헬스체크. Phase 2 에서 배운 그것."""
    return {"status": "UP"}


# =========================================================================
#  ★ 2026-09-02 추가 — 전문 위·변조 방지 (HMAC 서명 검증)
#
#  공공 연계 규격서에 "전문 위·변조 방지" / "서명값(signature)" 항목이 있으면
#  상대 기관 쪽은 이런 코드를 갖고 있다. 우리는 이걸 통과시켜야 한다.
#
#  ★ 왜 TLS 만으로 부족한가
#    TLS 는 '구간(hop)마다' 지킨다. 우리 -> nginx 구간은 지켜지지만,
#    nginx 가 암호를 풀고 뒷단 앱에 넘기기 전에 내용을 바꿔도 우리는 모른다.
#    실제 공공 연계는 [우리]->[DMZ웹서버]->[연계서버/ESB]->[업무시스템] 처럼
#    TLS 종료 지점이 여러 개다. 그래서 '출발지에서 도착지까지' 를 지키는
#    별도 장치가 필요하다. 그게 HMAC 이다.
# =========================================================================
import hmac
import hashlib
import time

# ★ 이 비밀키는 '양쪽이 같은 값' 을 갖고 있어야 한다.
#   API Key 와 성격이 같다(대칭). 실무에서는 공문/보안메일로 주고받고,
#   절대 소스에 박지 않는다. (여기서도 환경변수로 받는다)
HMAC_SECRET = os.getenv("HMAC_SECRET", "없음")


def build_canonical(timestamp: str, method: str, path: str, body: str) -> str:
    """
    ★ canonical string = '서명할 대상' 을 양쪽이 똑같은 순서·형식으로 조립한 문자열.

      규격서에 이 조립 순서가 반드시 적혀 있다. 한 글자라도 다르면 서명이 안 맞는다.
      실무에서 HMAC 이 안 맞을 때 원인 3개 중 2번이 바로 이 조립 순서다.

      여기서 정한 규격 (줄바꿈 \n 으로 이어붙인다):
          timestamp \n method \n path \n body
    """
    return "\n".join([timestamp, method, path, body])


def calc_signature(canonical: str) -> str:
    """
    HMAC-SHA256 을 계산해서 16진수 문자열로 돌려준다.

    ★ 인코딩을 반드시 명시한다(utf-8). 자바 쪽 getBytes(StandardCharsets.UTF_8) 과
      짝이 맞아야 한다. 한쪽이 cp949 면 한글이 든 요청만 서명이 틀린다.
    """
    return hmac.new(
        HMAC_SECRET.encode("utf-8"),
        canonical.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


async def verify_signature(request: Request, check_timestamp: bool):
    """서명을 검증한다. 통과하면 본문 문자열을 돌려주고, 아니면 예외를 던진다."""

    timestamp = request.headers.get("X-Timestamp", "")
    received  = request.headers.get("X-Signature", "")
    body      = (await request.body()).decode("utf-8")

    if not timestamp or not received:
        raise HTTPException(status_code=401, detail="서명 헤더 누락 (X-Timestamp / X-Signature)")

    # ---- 상대 기관은 '복호화' 하지 않는다. 같은 재료로 '다시 계산' 한다 ----
    canonical = build_canonical(timestamp, request.method, request.url.path, body)
    expected  = calc_signature(canonical)

    print(f"[서명검증] 받은값={received[:16]}... 계산값={expected[:16]}...")

    # ★ == 가 아니라 compare_digest 를 쓴다.
    #   == 는 앞에서부터 비교하다 다르면 즉시 멈춘다. 그 '멈추는 시간 차이' 로
    #   서명을 한 글자씩 알아내는 공격(타이밍 공격)이 가능하다.
    #   compare_digest 는 항상 같은 시간이 걸린다.
    if not hmac.compare_digest(received, expected):
        raise HTTPException(status_code=401, detail="서명 불일치 - 전문이 위조되었거나 비밀키가 다름")

    if check_timestamp:
        # ★ HMAC 만으로는 '재전송 공격' 을 못 막는다.
        #   정상 요청을 그대로 복사해 다시 보내면 서명도 그대로 유효하다.
        #   그래서 규격서에는 HMAC 과 timestamp 가 거의 항상 같이 있다.
        try:
            sent_at = int(timestamp)
        except ValueError:
            raise HTTPException(status_code=401, detail="X-Timestamp 형식 오류 (밀리초 정수여야 함)")

        age_sec = abs(time.time() - sent_at / 1000)
        if age_sec > 300:   # 5분
            raise HTTPException(
                status_code=401,
                detail=f"요청이 너무 오래됨 ({int(age_sec)}초 전) - 재전송 공격 의심",
            )

    return body


@app.post("/openapi/file/register")
async def register_file(request: Request):
    """
    ★ 1단계 창구 — HMAC 서명만 검증한다. timestamp 는 안 본다.

      이 창구는 재전송 공격에 열려 있다. 실습에서 직접 확인한다.
    """
    body = await verify_signature(request, check_timestamp=False)
    return {
        "resultCode": "00",
        "resultMsg":  "NORMAL SERVICE",
        "접수결과":    "서명 검증 통과. 등록 접수됨",
        "받은본문":    body,
    }


@app.post("/openapi/file/register-v2")
async def register_file_v2(request: Request):
    """
    ★ 2단계 창구 — HMAC + timestamp 를 함께 검증한다.

      5분보다 오래된 요청은 거절한다. 재전송 '창(window)' 을 5분으로 좁힌 것이다.
      ★ 완전히 막는 것은 아니다. 5분 안에 다시 보내면 여전히 통과한다.
        완전히 막으려면 nonce(1회용 난수)를 서버가 기억해서 재사용을 거부해야 한다.
    """
    body = await verify_signature(request, check_timestamp=True)
    return {
        "resultCode": "00",
        "resultMsg":  "NORMAL SERVICE",
        "접수결과":    "서명 + 시각 검증 통과. 등록 접수됨",
        "받은본문":    body,
    }


# =========================================================================
#  ★ 2026-09-06 추가 — OAuth2 토큰 발급 + JWT 검증
#
#  지금까지는 매 요청마다 API Key 원본을 실어 보냈다.
#  이제는 '한 번 받아서 잠깐 쓰는 임시 신분증(토큰)' 방식으로 바꾼다.
#
#  ★ OAuth2 와 JWT 는 다른 층이다. 자주 같이 쓰여서 한 덩어리로 오해한다.
#      OAuth2 = 토큰을 어떻게 주고받나 (절차)
#      JWT    = 그 토큰이 어떻게 생겼나 (형식)
#
#  ★ 여기 쓰는 흐름은 client_credentials.
#    OAuth2 흐름 중 '사람 로그인이 없는 시스템 대 시스템' 용이다.
#    (화면에서 '네이버로 로그인' 할 때 쓰는 흐름은 authorization_code 로 다르다.
#     연계에서는 사람이 없으므로 client_credentials 를 쓴다)
# =========================================================================
import jwt as pyjwt
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

# ---- HS256 용 비밀키 (대칭) ----
# ★ 이 값을 우리(연계 상대)에게도 줘야 검증이 된다. 그게 HS256 의 근본 문제다.
JWT_HS_SECRET = os.getenv("JWT_HS_SECRET", "없음")

# ---- RS256 용 키쌍 (비대칭) ----
# ★ 실무에서는 파일로 보관하지만, 여기서는 뜰 때마다 새로 만든다.
#   중요한 건 '두 개' 라는 것: 개인키로 서명하고 공개키로 검증한다.
_rsa_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

RS_PRIVATE_PEM = _rsa_key.private_bytes(
    encoding=serialization.Encoding.PEM,
    format=serialization.PrivateFormat.PKCS8,
    encryption_algorithm=serialization.NoEncryption(),
).decode()

RS_PUBLIC_PEM = _rsa_key.public_key().public_bytes(
    encoding=serialization.Encoding.PEM,
    format=serialization.PublicFormat.SubjectPublicKeyInfo,
).decode()

# 이 기관이 발급해준 클라이언트 자격증명 (연계 규격서에 적혀서 온다)
CLIENT_ID     = "our-si-company"
CLIENT_SECRET = os.getenv("PARTNER_CLIENT_SECRET", "없음")


@app.post("/oauth2/token")
async def issue_token(request: Request):
    """
    ★ 토큰 발급 창구.  OAuth2 client_credentials 흐름.

      규격상 이 창구는 JSON 이 아니라 form 형식(application/x-www-form-urlencoded)
      으로 받는다. RFC 6749 가 그렇게 정했다.
      ★ 실무 함정: 여기에 JSON 을 보내면 400 이 난다.
        "다른 API 는 다 JSON 인데 토큰만 form" 이라서 자주 틀린다.
    """
    form = await request.form()

    grant_type = form.get("grant_type", "")
    client_id  = form.get("client_id", "")
    secret     = form.get("client_secret", "")
    alg        = form.get("alg", "HS256")      # 학습용. 실제 규격엔 없는 항목이다.

    if grant_type != "client_credentials":
        raise HTTPException(status_code=400, detail=f"지원하지 않는 grant_type: {grant_type}")

    if client_id != CLIENT_ID or secret != CLIENT_SECRET:
        raise HTTPException(status_code=401, detail="client_id 또는 client_secret 불일치")

    now = int(time.time())
    payload = {
        "iss":   "partner-agency",       # issuer  - 누가 발급했나
        "sub":   client_id,              # subject - 누구에게 발급했나
        "scope": "file.read file.write", # 이 토큰으로 할 수 있는 일
        "iat":   now,                    # issued at - 발급 시각
        "exp":   now + 300,              # expiration - 만료 (5분)
    }

    if alg == "RS256":
        # 개인키로 서명한다. 검증하는 쪽은 공개키만 있으면 된다.
        token = pyjwt.encode(payload, RS_PRIVATE_PEM, algorithm="RS256",
                             headers={"kid": "partner-rsa-2026"})
    else:
        # 비밀키로 서명한다. 검증하는 쪽도 '같은 비밀키' 가 있어야 한다.
        token = pyjwt.encode(payload, JWT_HS_SECRET, algorithm="HS256",
                             headers={"kid": "partner-hs-2026"})

    print(f"[토큰발급] alg={alg} sub={client_id} exp={payload['exp']}")

    # 응답 형식도 OAuth2 규격이 정해놨다. 이름을 마음대로 못 짓는다.
    return {
        "access_token": token,
        "token_type":   "Bearer",
        "expires_in":   300,
        "scope":        payload["scope"],
    }


@app.get("/oauth2/jwks")
def jwks():
    """
    ★ 공개키를 나눠주는 창구.

      RS256 을 쓰면 검증하는 쪽이 '공개키' 를 알아야 한다.
      메일로 파일을 주고받는 대신 이렇게 HTTP 로 공개한다.
      실제 표준은 JWKS(JSON Web Key Set) 형식이지만, 학습용으로 PEM 을 그대로 준다.

      ★ 이 창구는 인증이 없다. 공개키는 '공개' 해도 되는 값이기 때문이다.
        -> HS256 이었다면 이런 창구를 절대 만들 수 없다. 그게 차이다.
    """
    return {"kid": "partner-rsa-2026", "alg": "RS256", "public_key_pem": RS_PUBLIC_PEM}


def verify_token(auth_header: str) -> dict:
    """Authorization 헤더에서 토큰을 꺼내 검증한다."""

    if not auth_header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Authorization 헤더가 없거나 Bearer 형식이 아님")

    token = auth_header[7:]

    # ★ 어떤 알고리즘으로 서명됐는지는 헤더에 적혀 있다. 먼저 열어본다.
    #   (이건 '검증' 이 아니라 그냥 읽는 것이다. 누구나 할 수 있다)
    try:
        head = pyjwt.get_unverified_header(token)
    except Exception as e:
        raise HTTPException(status_code=401, detail=f"토큰 형식 오류: {e}")

    alg = head.get("alg", "")

    # 🚨 알고리즘을 '토큰이 말하는 대로' 믿으면 안 된다.
    #   공격자가 alg 를 none 이나 다른 것으로 바꿔 보내는 공격이 있다.
    #   서버가 받아들일 알고리즘을 '우리가' 정해서 넘긴다.
    try:
        if alg == "RS256":
            claims = pyjwt.decode(token, RS_PUBLIC_PEM, algorithms=["RS256"])
        elif alg == "HS256":
            claims = pyjwt.decode(token, JWT_HS_SECRET, algorithms=["HS256"])
        else:
            raise HTTPException(status_code=401, detail=f"허용하지 않는 알고리즘: {alg}")

    except pyjwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="토큰 만료됨 - 재발급 필요")
    except pyjwt.InvalidSignatureError:
        raise HTTPException(status_code=401, detail="서명 불일치 - 토큰이 위조됨")
    except pyjwt.PyJWTError as e:
        raise HTTPException(status_code=401, detail=f"토큰 검증 실패: {e}")

    print(f"[토큰검증] alg={alg} sub={claims.get('sub')} scope={claims.get('scope')}")
    return claims


@app.get("/openapi/file/list-jwt")
def file_list_jwt(authorization: str = Header(default="")):
    """
    ★ 토큰으로 보호되는 창구. API Key 대신 토큰을 본다.

      돌려주는 내용은 기존 list 와 같다. 달라진 건 '문을 여는 방법' 뿐이다.
    """
    claims = verify_token(authorization)

    return {
        "resultCode": "00",
        "resultMsg":  "NORMAL SERVICE",
        "인증정보": {
            "누구":     claims.get("sub"),
            "발급자":   claims.get("iss"),
            "권한범위": claims.get("scope"),
        },
        "totalCount": len(FILE_TABLE),
        "items":      FILE_TABLE,
    }

@app.post("/oauth2/token-expired")
async def issue_expired_token():
    """
    ★ 학습 전용 — '이미 만료된' 토큰을 발급한다. 실제 기관 API 에는 없다.

      서명은 완전히 정상이다. exp 만 과거다.
      -> 서명 검증은 통과하고 만료 검증에서 걸린다. 둘은 다른 검사다.
    """
    now = int(time.time())
    payload = {
        "iss": "partner-agency",
        "sub": CLIENT_ID,
        "scope": "file.read file.write",
        "iat": now - 3600,
        "exp": now - 3000,        # 50분 전에 이미 만료됨
    }
    token = pyjwt.encode(payload, JWT_HS_SECRET, algorithm="HS256",
                         headers={"kid": "partner-hs-2026"})
    return {"access_token": token, "token_type": "Bearer", "expires_in": -3000}
