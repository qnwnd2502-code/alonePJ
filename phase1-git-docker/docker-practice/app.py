"""컨테이너 안에서 도는 걸 눈으로 확인하는 작은 API."""
import os
import platform
import secrets
import socket
import time

import psycopg
import redis
from fastapi import Cookie, FastAPI, HTTPException, Response

app = FastAPI(title="Docker 실습")


@app.get("/")
def root():
    return {"message": "컨테이너에서 인사드립니다"}


@app.get("/where-am-i")
def where_am_i():
    """지금 이 코드가 어디서 도는지 알려준다."""
    return {
        "hostname": socket.gethostname(),   # 컨테이너 ID가 찍힌다
        "os": platform.system(),            # Linux 라고 나온다 (윈도우가 아니라!)
        "kernel": platform.release(),       # WSL2 커널을 빌려 쓴다는 증거
        "python": platform.python_version(),
        "실행_환경": os.getenv("APP_ENV", "설정 안 됨"),
    }

DATABASE_URL = os.environ["DATABASE_URL"]
@app.get("/db-check")
def db_check():
    """DB에 실제로 질의를 던져보고 결과를 돌려줌"""
    with psycopg.connect(DATABASE_URL) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT version()")
            version = cur.fetchone()[0]
        return {"연결" : "성공", "postgres" : version}


@app.get("/version")
def version():
    """이 앱의 버전을 알려준다."""
    return {"version": os.getenv("APP_VERSION", "0.1.0")}


@app.get("/health")
def health():
    """컨테이너가 살아 있는지 확인하는 헬스체크용 엔드포인트."""
    return {"status": "ok"}


@app.get("/slow")
def slow(seconds: int = 10):
    """일부러 느리게 응답한다. 504 재현용.

    실무에서 이 자리에 들어가는 것: 인덱스 없는 대용량 조회, 무한 루프,
    응답 없는 외부 API 호출. 증상은 전부 똑같다 — 앱은 살아있는데 답이 안 온다.
    """
    time.sleep(seconds)
    return {"slept": seconds, "hostname": socket.gethostname()}


# 어제까지 여기엔 SESSIONS = {} (파이썬 딕셔너리)가 있었다.
# 그건 '이 컨테이너의 메모리 안'에만 있어서, api가 3대면 장부도 3개였다 -> 세션 불일치.
# 이제 장부를 서버 밖으로 뺀다. api 3대가 아래 한 곳을 같이 본다.
#
# decode_responses=True 를 켜는 이유: Redis는 원래 바이트(b'hong')를 돌려준다.
# 안 켜면 문자열 비교가 조용히 실패한다. 초보자가 꼭 한 번 밟는 지뢰.
REDIS_URL = os.environ["REDIS_URL"]
r = redis.Redis.from_url(REDIS_URL, decode_responses=True)

SESSION_TTL = 1800  # 30분. Redis가 알아서 지워준다 -> 자동 로그아웃이 공짜로 생김


def session_key(session_id: str) -> str:
    """Redis 한 대를 여러 앱이 나눠 쓰므로, 키에 이름표를 붙여 충돌을 막는다."""
    return f"session:{session_id}"


@app.post("/login")
def login(response: Response, user: str = "hong"):
    """로그인 흉내. 비밀번호 검사는 생략하고 세션만 발급한다."""
    session_id = secrets.token_hex(8)   # 추측 불가능한 무작위 번호표

    # setex = SET + EXpire. 저장과 동시에 수명을 박는다.
    # 어제 딕셔너리는 세션이 영원히 쌓여서 메모리가 계속 차올랐다.
    r.setex(session_key(session_id), SESSION_TTL, user)

    # 이 쿠키가 브라우저에 저장되고, 다음 요청부터 자동으로 따라온다.
    response.set_cookie("session_id", session_id)

    return {
        "로그인": "성공",
        "발급한_서버": socket.gethostname(),
        # 이제 이 숫자는 '이 서버가 아는 수'가 아니라 '전체가 공유하는 수'다.
        "전체_세션수": r.dbsize(),
    }


@app.get("/me")
def me(session_id: str | None = Cookie(default=None)):
    """번호표를 들고 와서 '나 누구였지?' 하고 묻는 곳."""
    here = socket.gethostname()

    if session_id is None:
        raise HTTPException(status_code=401, detail="쿠키가 없다. 로그인부터.")

    user = r.get(session_key(session_id))
    if user is None:
        # 이제 None 이 나오는 경우는 하나뿐이다 — 진짜로 없거나, 30분이 지나 만료됐거나.
        # '발급한 서버가 아니라서' 는 더 이상 원인이 될 수 없다.
        raise HTTPException(status_code=401, detail="세션이 없거나 만료됐다.")

    return {"user": user, "확인한_서버": here}


@app.post("/logout")
def logout(session_id: str | None = Cookie(default=None)):
    """세션을 창고에서 지운다. 지운 순간 모든 서버에서 즉시 로그아웃된다.

    토큰(JWT)으로는 이게 어렵다 — 서버가 기억하지 않으니 되돌릴 것도 없고,
    만료 시각까지는 유효한 채로 남는다. 공공기관이 세션을 놓지 못하는 이유.
    """
    if session_id is None:
        return {"로그아웃": "쿠키가 없어서 할 일 없음"}

    deleted = r.delete(session_key(session_id))   # 지운 개수(0 또는 1)를 돌려준다
    return {"로그아웃": "성공" if deleted else "이미 없던 세션", "남은_세션수": r.dbsize()}
