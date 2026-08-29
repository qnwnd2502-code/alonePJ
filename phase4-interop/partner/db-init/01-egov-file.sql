-- ============================================================
--  '상대 기관' 의 DB. 우리가 만든 게 아니라 상대가 20년째 쓰고 있는 것이다.
--  전자정부 표준 첨부파일 테이블(COMTNFILEDETAIL) 을 흉내 냈다.
--  Phase 3 5일차에 본 그 이름들이다.
-- ============================================================
CREATE TABLE comtnfiledetail (
    atch_file_id     VARCHAR(20)  NOT NULL,   -- 첨부파일 ID
    file_sn          INTEGER      NOT NULL,   -- 파일 순번
    orignl_file_nm   VARCHAR(255) NOT NULL,   -- 원래 파일명
    stre_file_nm     VARCHAR(255) NOT NULL,   -- 저장 파일명
    file_stre_cours  VARCHAR(255) NOT NULL,   -- 저장 경로 (상대 서버의 로컬 경로)
    file_mg          VARCHAR(20),             -- 크기(byte)
    use_yn           CHAR(1)      DEFAULT 'Y',
    creat_dt         DATE         DEFAULT now(),
    PRIMARY KEY (atch_file_id, file_sn)
);

INSERT INTO comtnfiledetail VALUES
 ('FILE_000000000000123', 1, '민원신청서.pdf',     'a3f9c8e2-4b1d.txt', '/data/upload/2026/08/', '2048', 'Y', '2026-08-20'),
 ('FILE_000000000000124', 1, '처리결과통보서.hwp', '7c1e0b55-92aa.txt', '/data/upload/2026/08/', '5120', 'Y', '2026-08-22'),
 ('FILE_000000000000125', 1, '폐기예정문서.docx',  'ff00aa11-3c7e.txt', '/data/upload/2026/07/',  '900', 'N', '2026-07-11');

-- ============================================================
--  ★ 절충안: 상대가 우리에게 열어주는 '뷰(View)'.
--    우리는 테이블이 아니라 이것만 본다.
--    -> 상대가 안쪽 테이블을 바꿔도 이 뷰만 유지하면 우리는 안 깨진다.
--    -> 이게 'DB 직접 연계' 라고 부르는 것의 실제 모습이다.
-- ============================================================
CREATE VIEW v_file_for_partner AS
SELECT atch_file_id    AS file_id,
       orignl_file_nm  AS file_name,
       file_mg         AS file_size
  FROM comtnfiledetail
 WHERE use_yn = 'Y';
