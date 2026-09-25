# -*- coding: utf-8 -*-
"""
=========================================================================
 ★ 우리 AI 제품 (FastAPI + uvicorn).

 지금까지와 자리가 바뀌었다.
   Phase 4     우리가 '부르는 쪽' 이었다
   여기부터    ★ 우리가 '불리는 쪽' 이다.  기관 시스템이 우리를 부른다

 기관 전자정부(Java) → [HTTP] → 여기 → 답변 → JSP 렌더
=========================================================================
"""
from fastapi import FastAPI, Request

app = FastAPI(title="우리 AI 서비스")


@app.get("/ai/health")
async def health():
    return {"status": "UP"}


@app.get("/ai/ask")
async def ask_get(q: str = ""):
    """질문을 쿼리스트링으로 받는다."""
    return _answer(q, "GET 쿼리스트링")


@app.post("/ai/ask")
async def ask_post(request: Request):
    """질문을 JSON 본문으로 받는다."""
    body = await request.json()
    return _answer(body.get("question", ""), "POST JSON 본문")


def _answer(q, 경로):
    # 우리가 '실제로 받은 글자' 를 그대로 보여준다.
    # 한글이 깨졌는지는 여기서만 정확히 알 수 있다.
    return {
        "받은경로": 경로,
        "받은질문": q,
        "받은글자수": len(q),
        "받은바이트(UTF-8)": len(q.encode("utf-8")),
        "답변": "OO항 입항 절차는 ... (AI 답변이라고 치자)" if q else "질문이 비었습니다",
    }


# =========================================================================
#  실습 15 : 사용자별 문서 검색
#
#  AI 서버는 '누가 물었는지' 를 X-User-Id 헤더로 받는다.
#  그 사람 부서의 문서만 찾아준다. (권한별 검색 — 실습 8 의 그것)
# =========================================================================
from fastapi import Header, HTTPException

USERS = {"hong": "기획부", "kim": "총무부", "lee": "감사실"}

DOCS = [
    {"id": "D-01", "title": "2026 사업계획",        "dept": "기획부"},
    {"id": "D-02", "title": "예산 편성 기준",        "dept": "기획부"},
    {"id": "D-03", "title": "청사 관리 지침",        "dept": "총무부"},
    {"id": "D-04", "title": "물품 구매 절차",        "dept": "총무부"},
    {"id": "D-05", "title": "내부감사 결과보고",      "dept": "감사실"},
    {"id": "D-06", "title": "감사 지적사항 조치계획",  "dept": "감사실"},
]


@app.get("/ai/search")
async def search(q: str = "", x_user_id: str = Header(default="")):
    dept = USERS.get(x_user_id)
    if not dept:
        raise HTTPException(status_code=401, detail="누가 묻는지 모릅니다 (X-User-Id 없음)")
    hits = [d for d in DOCS if d["dept"] == dept and q in d["title"]]
    return {"AI가받은사용자": x_user_id, "부서": dept, "검색결과": hits}
