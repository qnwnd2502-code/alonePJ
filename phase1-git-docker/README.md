# Phase 1 — Git + Docker 팀 협업

## 목표

명령어를 외우는 게 아니라, **손이 기억하도록** 반복한다.

## Git 미션

- [ ] feature 브랜치 → PR → 머지 사이클 10회
- [ ] 의도적으로 충돌(conflict) 만들고 해결해보기
- [ ] `reset` / `revert` / `stash` / `reflog` 로 실수 복구 연습
- [ ] `rebase` 로 커밋 히스토리 정리해보기

### 자주 쓰는 명령어

```bash
git switch -c feature/이름     # 브랜치 만들며 이동
git add -A                     # 변경사항 전체 스테이징
git commit -m "feat: 요약"     # 커밋
git push -u origin feature/이름 # 원격에 올리기
git switch main                # main으로 복귀
git pull                       # 최신 내려받기
```

## Docker 미션

- [x] `docker run hello-world` 성공
- [x] 컨테이너 생명주기 다뤄보기 (`run` / `ps` / `exec` / `logs` / `stop` / `rm`)
- [x] Dockerfile 직접 작성해서 이미지 빌드
- [x] docker-compose 로 FastAPI + PostgreSQL 동시 실행
- [x] 컨테이너 간 네트워크 통신 확인

### 자주 쓰는 명령어

```bash
docker ps -a                   # 컨테이너 목록 (중지된 것 포함)
docker logs -f 컨테이너명       # 로그 실시간 보기
docker exec -it 컨테이너명 bash # 컨테이너 안으로 들어가기
docker compose up -d           # 백그라운드로 서비스 전체 실행
docker compose down            # 서비스 전체 종료·정리
```

## 학습 기록

진행하면서 막혔던 부분과 해결 과정을 여기에 남긴다.

| 날짜       | 막힌 것                                                                                              | 원인                                                                                                        | 해결                                                                 |
| ---------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| 2026-08-08 | `git push` 시 `src refspec main does not match any`                                                  | 커밋이 하나도 없어서 main 브랜치가 실체가 없었음 (파일명 오타로 add 실패)                                   | 파일명 수정 후 정상 커밋 → push 성공                                 |
| 2026-08-09 | 머지된 브랜치를 `git push origin --delete` 했더니 `remote ref does not exist`                        | GitHub가 PR 머지 시 원격 브랜치를 자동 삭제함. 원격엔 이미 없었고 내 PC에만 낡은 추적 기록이 남아 있었던 것 | `git remote prune origin` 으로 정리. `--delete`는 애초에 필요 없었음 |
| 2026-08-09 | git status로 modified와 Untracked files의 차이를 알았고 git commit -m 시 의미 단위로 쪼개는걸 알았음 |
