# -*- coding: utf-8 -*-
"""
=========================================================================
 ★ 외부 LLM API — 클라우드 업체 서버 (실습 17)

 우리 것이 아니다. 원래는 인터넷 건너편, 남의 회사 전산실에 있다.
 ★ 현실에서는 이 서버의 로그를 우리가 '볼 수 없다'.
   여기 뭐가 도착했는지는 업체만 안다. 한번 넘어가면 우리가 지울 수도 없다.

 학습용으로 창문을 하나 냈다 :  docker compose logs llm-ext
=========================================================================
"""
from fastapi import FastAPI, Request

app = FastAPI(title="외부 LLM API (가상)")


@app.post("/v1/chat")
async def chat(request: Request):
    body = await request.json()
    prompt = body.get("prompt", "")
    print(f"[llm-ext] 업체 서버가 받은 프롬프트 : {prompt!r}", flush=True)
    return {"answer": "문의하신 내용은 OO공단 누리집 > 민원 > 조회 메뉴에서 확인하실 수 있습니다. (AI 답변이라고 치자)"}
