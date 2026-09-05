FROM python:3.12-slim
WORKDIR /app
# PyJWT: 토큰 발급/검증.  cryptography: RS256(RSA 서명)에 필요하다.
# ★ HS256 만 쓸 거면 cryptography 는 필요 없다. RSA 를 쓰는 순간 추가된다.
# python-multipart: form 형식(application/x-www-form-urlencoded) 파싱용.
#   ★ OAuth2 토큰 창구는 JSON 이 아니라 form 으로 받는다(RFC 6749). 그래서 필요하다.
RUN pip install --no-cache-dir fastapi==0.115.0 "uvicorn[standard]==0.30.6" "PyJWT==2.9.0" "cryptography==43.0.1" "python-multipart==0.0.12"
COPY app.py /app/app.py
COPY files /app/files
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
