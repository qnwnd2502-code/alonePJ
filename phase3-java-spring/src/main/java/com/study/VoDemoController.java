package com.study;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

// Map 방식 vs VO 방식 비교용.
//
// 같은 오타(useYn -> useYnn)를 양쪽에 내보면 반응이 정반대다.
//   row.get("useYnn")  -> 컴파일 통과. 값만 null. 에러 0줄. 발주처가 발견한다
//   vo.getUseYnn()     -> cannot find symbol: method getUseYnn(). 서버가 안 뜬다. 내가 발견한다
//
// 이유: Map 의 키는 그냥 '글자' 라서 자바가 검사할 방법이 없다.
//       VO 의 getUseYn() 은 '메서드' 라서 자바가 MemberVO 를 열어보고 확인한다.
//       List<String> 에 숫자를 넣었을 때 서버가 안 뜬 것과 같은 원리다.
@RestController
public class VoDemoController {

    @GetMapping("/vo-demo")
    public Map<String, Object> voDemo() {

        // ── 1) Map 방식 (어제 방식) ──
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "김용준");
        row.put("useYn", "Y");

        // ── 2) VO 방식 (오늘 방식) ──
        MemberVO vo = new MemberVO();
        vo.setName("김용준");
        vo.setUseYn("Y");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("map_useYn", row.get("useYn"));
        result.put("vo_useYn", vo.getUseYn());
        return result;
    }
}
