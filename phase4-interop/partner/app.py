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
