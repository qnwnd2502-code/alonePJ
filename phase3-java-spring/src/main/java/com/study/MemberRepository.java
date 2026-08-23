package com.study;

import org.springframework.stereotype.Repository;
import java.util.List;

// @Repository = "너는 데이터 담당이다"
// 회사에서는 이자리에 Mybatis Mapper 가 들어가고, 여기서 SQL을 날린다.

@Repository
public class MemberRepository{

    private final List<String> members = List.of("김용준","곽민수","유준영");

    public String findByName(String name){
        for (String m : members){
            if(m.equals(name)){
                return m;
            }
        }
        return null;
    }
}