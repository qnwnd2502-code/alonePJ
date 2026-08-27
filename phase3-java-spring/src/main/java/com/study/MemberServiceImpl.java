package com.study;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

// ============================================
//  구현체 = 본문
//  implements MemberService = "나 저 규격 지킬게" 라는 선언
//  전자정부 소스의 ○○ServiceImpl.java 가 이 자리다. 로직은 전부 여기 있다.
// ============================================
@Service
public class MemberServiceImpl extends AbstractStudyServiceImpl implements MemberService {

    @Autowired
    private MemberRepository memberRepository;

    @Override   // "위 규격에 있는 그 메서드를 내가 채운다" 는 표시
    public String greet(String name) {
        String found = memberRepository.findByName(name);

        if (found == null) {
            return name + "님은 명단에 없습니다";
        }
        leaveaTrace(name + "조회 성공");
        return found.trim() + "님 안녕하세요";
    }
}
