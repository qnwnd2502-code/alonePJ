package com.study;

import org.springframework.stereotype.Service;

// 같은 규격(MemberService)을 지키는 두 번째 구현체. 영어로 인사한다.
//
// 일부러 남겨둔 파일이다. 이게 있어야 후보가 2개가 되고,
// 그래서 HelloController 가 @Resource(name=...) 로 '지목' 해야만 서버가 뜬다.
// 지우면 왜 지목이 필요한지가 코드에서 사라진다.
//
// 지운 채로 띄우면 이 에러를 만난다:
//   Field memberService in com.study.HelloController required a single bean, but 2 were found
@Service
public class EnglishMemberServiceImpl implements MemberService {

    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
