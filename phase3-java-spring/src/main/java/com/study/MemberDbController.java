package com.study;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 오늘 만든 Mapper 를 눈으로 확인하는 창구.
// 원래는 Controller -> Service -> Mapper 를 거치지만,
// 지금은 Mapper 만 보려고 Service 를 생략했다(수업용).
@RestController
public class MemberDbController {

    // 구현체를 만든 적이 없는데 주입이 된다. MyBatis 가 만들어 꽂아준다.
    @Resource
    private MemberMapper memberMapper;

    // 1) VO 로 받기
    @GetMapping("/db/members")
    public List<MemberVO> members() {
        return memberMapper.selectMemberList();
    }

    // 2) Map 으로 받기 -- 전자정부 구형 소스 방식
    @GetMapping("/db/members-map")
    public List<Map<String, Object>> membersAsMap() {
        return memberMapper.selectMemberListAsMap();
    }

    // 3) 이름으로 한 건. 어제 배운 'int 계수기' 도 같이 넣어봤다
    @GetMapping("/db/member")
    public Map<String, Object> member(@RequestParam String name) {
        MemberVO vo = memberMapper.selectMemberByName(name);

        Map<String, Object> result = new LinkedHashMap<>();
        if (vo == null) {
            result.put("message", name + "님은 DB에 없습니다");
            return result;
        }
        result.put("name", vo.getName());
        result.put("useYn", vo.getUseYn());
        // 어제 배운 것: "Y".equals(x) 로 써야 x 가 null 이어도 안 터진다
        result.put("사용중", "Y".equals(vo.getUseYn()));
        return result;
    }
}
