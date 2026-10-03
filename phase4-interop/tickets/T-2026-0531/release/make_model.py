# -*- coding: utf-8 -*-
"""
가상의 모델 가중치 파일을 만든다. (실습용. 진짜 모델이 아니다)

모델 파일은 용량이 커서 git 에 올리지 않는다(.gitignore). 레포를 새로 받았으면 이걸 한 번 돌린다.
    python make_model.py
항상 같은 내용이 만들어지므로 해시도 항상 같다. (릴리스노트.md 의 값과 같아야 한다)
"""
import hashlib
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model", "embedding-v1.bin")
SIZE_MB = 8

os.makedirs(os.path.dirname(OUT), exist_ok=True)
block = b"OO-AI embedding-v1"
h = hashlib.sha256()
with open(OUT, "wb") as f:
    written = 0
    while written < SIZE_MB * 1024 * 1024:
        block = hashlib.sha256(block).digest() * 1024      # 32KB 씩
        f.write(block)
        h.update(block)
        written += len(block)

print(OUT)
print("sha256", h.hexdigest())
