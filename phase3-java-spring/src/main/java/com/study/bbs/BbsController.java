package com.study.bbs;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 공지사항 게시판
 */
@RestController
public class BbsController {

    @Resource(name = "bbsService")
    private BbsService bbsService;

    /** 공지사항 목록 */
    @GetMapping("/bbs/selectBbsList.do")
    public Map<String, Object> selectBbsList(@ModelAttribute("searchVO") BoardVO searchVO) {
        return bbsService.selectBbsList(searchVO);
    }
    /** 공지사항 상세 */
    @GetMapping("/bbs/selectBbs.do")
    public Map<String, Object> selectBbs(@RequestParam int nttId){
        return bbsService.selectBbs(nttId);
    }
}
