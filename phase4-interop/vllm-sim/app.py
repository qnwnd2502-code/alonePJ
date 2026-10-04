# -*- coding: utf-8 -*-
"""
=========================================================================
 ★ vLLM 흉내 서버 (실습 20)

 진짜 vLLM 은 GPU 에 모델을 올려서 돌린다. 여기엔 GPU 가 없으니 '흉내' 만 낸다.
   - 기동할 때 모델 크기와 GPU 메모리를 비교한다
   - 모자라면 진짜 vLLM 처럼 CUDA out of memory 를 내고 죽는다
   - 넉넉하면 /v1/chat 으로 답한다

 GPU 정보는 /etc/gpu-info 에서 읽는다 (진짜 서버라면 드라이버가 알려주는 값)
 모델은 /models/<MODEL_NAME>/config.json 이 있어야 한다 (진짜라면 수십 GB 가중치)
=========================================================================
"""
import json
import os
import sys

from fastapi import FastAPI, Request

MODEL_NAME = os.environ.get("MODEL_NAME", "")
GPU_UTIL = float(os.environ.get("GPU_MEMORY_UTILIZATION", "0.90"))


def log(level, msg):
    print(f"{level} [vllm] {msg}", flush=True)


def read_gpu():
    info = {}
    with open("/etc/gpu-info", encoding="utf-8") as f:
        for line in f:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                info[k] = v
    return info["GPU_NAME"], float(info["GPU_MEM_GIB"])


def boot():
    log("INFO", f"vLLM API server version 0.6.3 (sim)")
    log("INFO", f"Initializing an LLM engine with config: model='/models/{MODEL_NAME}', "
                f"dtype=bfloat16, gpu_memory_utilization={GPU_UTIL}")

    cfg_path = f"/models/{MODEL_NAME}/config.json"
    if not os.path.exists(cfg_path):
        log("ERROR", f"OSError: /models/{MODEL_NAME} does not appear to have a file named config.json")
        sys.exit(1)
    with open(cfg_path, encoding="utf-8") as f:
        need = float(json.load(f)["weights_gib"])

    gpu_name, total = read_gpu()
    usable = round(total * GPU_UTIL, 2)
    log("INFO", f"GPU 0: {gpu_name}, total memory {total} GiB, usable {usable} GiB")
    log("INFO", f"Loading model weights took ... (weights {need} GiB)")

    if need > usable:
        print("Traceback (most recent call last):", flush=True)
        print('  File "/usr/local/lib/python3.12/site-packages/vllm/worker/model_runner.py", line 1047, in load_model', flush=True)
        print("    self.model = get_model(...)", flush=True)
        print(f"torch.OutOfMemoryError: CUDA out of memory. Tried to allocate 1.02 GiB. "
              f"GPU 0 has a total capacity of {total} GiB of which 412.00 MiB is free. "
              f"Of the allocated memory {usable} GiB is allocated by PyTorch.", flush=True)
        log("ERROR", "RuntimeError: Engine core initialization failed. See root cause above.")
        sys.exit(1)

    log("INFO", f"Model loaded. Available KV cache memory: {round(usable - need, 2)} GiB")
    log("INFO", "Uvicorn running on http://0.0.0.0:8000")


boot()
app = FastAPI(title="vLLM (sim)")


@app.get("/health")
async def health():
    return {"status": "ok", "model": MODEL_NAME}


@app.post("/v1/chat")
async def chat(request: Request):
    body = await request.json()
    print(f"INFO [vllm] prompt received ({len(body.get('prompt', ''))} chars)", flush=True)
    return {"answer": f"[{MODEL_NAME}] 문의하신 내용은 OO공단 누리집 > 민원 > 조회 메뉴에서 확인하실 수 있습니다."}
