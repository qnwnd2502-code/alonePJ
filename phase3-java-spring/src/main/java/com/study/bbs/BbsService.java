package com.study.bbs;

import java.util.Map;

public interface BbsService {

    /** 공지사항 목록과 페이징 정보 */
    Map<String, Object> selectBbsList(BoardVO searchVO);
    /** 공지사항 상세 */
    Map<String, Object> selectBbs(int nttId);
}
