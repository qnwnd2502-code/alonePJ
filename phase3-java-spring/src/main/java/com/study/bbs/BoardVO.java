package com.study.bbs;

/**
 * 게시판 검색 조건 VO
 */
public class BoardVO {

    /** 검색어 */
    private String searchKeyword = "";

    /** 현재 페이지 */
    private int pageIndex = 1;

    /** 페이지당 건수 */
    private int recordCountPerPage = 10;

    public String getSearchKeyword() { return searchKeyword; }
    public void setSearchKeyword(String searchKeyword) { this.searchKeyword = searchKeyword; }

    public int getPageIndex() { return pageIndex; }
    public void setPageIndex(int pageIndex) { this.pageIndex = pageIndex; }

    public int getRecordCountPerPage() { return recordCountPerPage; }
    public void setRecordCountPerPage(int recordCountPerPage) { this.recordCountPerPage = recordCountPerPage; }

    /** 조회 시작 위치 */
    public int getFirstIndex() { return (pageIndex - 1) * recordCountPerPage; }
}
