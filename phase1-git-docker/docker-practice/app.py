"""컨테이너 안에서 도는 걸 눈으로 확인하는 작은 API."""
import os
import platform
import socket

import psycopg
from fastapi import FastAPI

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


@app.get("/health")
def health():
    """컨테이너가 살아 있는지 확인하는 헬스체크용 엔드포인트."""
    return {"status": "ok"}
