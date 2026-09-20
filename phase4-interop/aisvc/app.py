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
        "답변": "울산항 입항 절차는 ... (AI 답변이라고 치자)" if q else "질문이 비었습니다",
    }
