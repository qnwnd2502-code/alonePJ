package com.study.bbs;

import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service("bbsService")
public class BbsServiceImpl implements BbsService {

    @Resource
    private BbsMapper bbsMapper;

    @Override
    public Map<String, Object> selectBbsList(BoardVO searchVO) {
        List<Map<String, Object>> resultList = bbsMapper.selectBbsList(searchVO);
        int totCnt = bbsMapper.selectBbsListCnt(searchVO);

        int totalPage = (totCnt + searchVO.getRecordCountPerPage() - 1) / searchVO.getRecordCountPerPage();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("검색어", searchVO.getSearchKeyword());
        out.put("현재페이지", searchVO.getPageIndex());
        out.put("총건수", totCnt);
        out.put("총페이지", totalPage);
        out.put("resultList", resultList);
        return out;
    }
    @Override
    public Map<String, Object> selectBbs(int nttId){
        Map<String,Object> bbs = bbsMapper.selectBbs(nttId);

        if (bbs == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"없는 게시물입니다");
        }
        return bbs;
    }
}
