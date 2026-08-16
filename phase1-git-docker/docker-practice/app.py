"""컨테이너 안에서 도는 걸 눈으로 확인하는 작은 API."""
import os
import platform
import secrets
import socket
import time

import psycopg
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


# 로그인한 사람을 적어두는 장부. 딱 하나의 사실만 기억할 것 —
# 이건 '이 컨테이너의 메모리 안'에만 있다. api 컨테이너가 3대면 이 딕셔너리도 3개고,
# 서로 내용을 전혀 모른다. 세션 불일치의 범인이 바로 이 한 줄이다.
# (실무에서 이 자리에 해당하는 것: Tomcat이 들고 있는 HttpSession)
SESSIONS: dict[str, str] = {}


@app.post("/login")
def login(response: Response, user: str = "hong"):
    """로그인 흉내. 비밀번호 검사는 생략하고 세션만 발급한다."""
    session_id = secrets.token_hex(8)   # 추측 불가능한 무작위 번호표
    SESSIONS[session_id] = user

    # 이 쿠키가 브라우저에 저장되고, 다음 요청부터 자동으로 따라온다.
    response.set_cookie("session_id", session_id)

    return {
        "로그인": "성공",
        "발급한_서버": socket.gethostname(),
        "이_서버가_아는_세션수": len(SESSIONS),
    }


@app.get("/me")
def me(session_id: str | None = Cookie(default=None)):
    """번호표를 들고 와서 '나 누구였지?' 하고 묻는 곳."""
    here = socket.gethostname()

    if session_id is None:
        raise HTTPException(status_code=401, detail="쿠키가 없다. 로그인부터.")

    user = SESSIONS.get(session_id)
    if user is None:
        # 번호표는 진짜인데, 하필 발급하지 않은 서버로 요청이 갔다.
        # 사용자 눈에는 '멀쩡히 로그인했는데 갑자기 풀림' 으로 보인다.
        # 실무에서는 서버 이름을 응답에 노출하지 않는다(내부 구조 노출). 학습용으로만 드러냄.
        raise HTTPException(
            status_code=401,
            detail=f"이 서버({here})는 그 세션을 모른다. 다른 서버가 발급했다.",
        )

    return {"user": user, "확인한_서버": here}
