package com.study.bbs;

import java.util.Map;

public interface BbsService {

    /** 공지사항 목록과 페이징 정보 */
    Map<String, Object> selectBbsList(BoardVO searchVO);
}
