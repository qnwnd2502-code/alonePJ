package com.study.bbs;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface BbsMapper {

    List<Map<String, Object>> selectBbsList(BoardVO searchVO);

    int selectBbsListCnt(BoardVO searchVO);
}
