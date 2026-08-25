-- ============================================================
--  이 폴더(db-init)의 .sql 파일은 DB가 '맨 처음 만들어질 때' 딱 한 번 자동 실행된다.
--  postgres 이미지가 제공하는 기능이다. (두 번째 기동부터는 실행되지 않는다)
--  다시 실행시키려면 볼륨을 지워야 한다: docker compose down -v
-- ============================================================

CREATE TABLE member (
    member_id   SERIAL PRIMARY KEY,          -- SERIAL = 자동으로 1,2,3... 증가
    name        VARCHAR(50)  NOT NULL,
    use_yn      CHAR(1)      NOT NULL DEFAULT 'Y',
    reg_dt      TIMESTAMP    NOT NULL DEFAULT now()
);

INSERT INTO member (name, use_yn) VALUES ('김용준', 'Y');
INSERT INTO member (name, use_yn) VALUES ('곽민수', 'Y');
INSERT INTO member (name, use_yn) VALUES ('유준영', 'N');
