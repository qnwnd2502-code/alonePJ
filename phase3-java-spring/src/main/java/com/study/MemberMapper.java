package com.study;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

// ============================================================
//  Mapper 인터페이스. 회사 소스의 ○○Mapper.java / ○○DAO.java 자리다.
//
//  ★ 오늘의 핵심: 이 인터페이스의 '구현체(Impl)' 를 우리가 만들지 않는다. ★
//    어제는 MemberService(목차) 와 MemberServiceImpl(본문) 을 둘 다 썼다.
//    여기는 본문이 없다. MyBatis 가 XML 을 보고 실행 중에 알아서 만들어 꽂아준다.
//    그래서 회사 소스에서 ○○Mapper 의 짝을 찾아도 안 나온다 -- 없는 게 정상이다.
//
//  @Mapper = "스프링아, 이 인터페이스는 MyBatis 가 채울 거야. 빈으로 등록해둬"
//            (@Service / @Repository 와 같은 자리의 표식)
//
//  ★ 규칙: 아래 메서드 이름이 XML 의 <select id="..."> 와 글자 하나까지 같아야 한다. ★
// ============================================================
@Mapper
public interface MemberMapper {

    // 1) VO 로 받기 -- 요즘 방식. 오타를 자바가 잡아준다
    List<MemberVO> selectMemberList();

    // 2) Map 으로 받기 -- 전자정부 구형 소스 방식. 어제 읽은 그 List<Map<String,Object>>
    List<Map<String, Object>> selectMemberListAsMap();

    // 3) 이름으로 한 건 찾기 -- 파라미터가 SQL 로 넘어간다
    MemberVO selectMemberByName(String name);
}
