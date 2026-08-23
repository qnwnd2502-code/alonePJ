package com.study;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service //스프링아 이 틀좀 맡아줘
public class Greeter{

    // Service 가 Repository 를 받아 쓴다. 여기도 new 가 없다.
    @Autowired
    private MemberRepository memberRepository;

    public String greet(String name) {
        String found = memberRepository.findByName(name);

        // 빈손인지 먼저 확인한다. 이 3줄이 없으면 500 이 난다.
        if (found == null) {
            return name + "님은 명단에 없습니다";
        }
        return found.trim() + "님 안녕하세요";
    }
}