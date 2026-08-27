package com.study;

import java.util.LinkedHashMap;

// ============================================================
//  전자정부의 EgovMap 을 흉내 낸 클래스.
//
//  ★ 오늘의 핵심 질문: Map 인데 왜 따로 만들어야 하나? ★
//
//  application.properties 의 이 설정을 4일차에 켰다:
//      mybatis.configuration.map-underscore-to-camel-case=true
//
//  그런데 이 설정은 VO(도시락통) 에만 듣는다. Map 에는 안 듣는다.
//    - VO   : MyBatis 가 setUseYn() 을 찾아 부른다 -> 밑줄->낙타등 변환이 여기서 일어남
//    - Map  : MyBatis 가 그냥 put("use_yn", 값) 을 한다 -> 변환할 대상이 없음
//
//  그래서 전자정부는 'put 할 때 키 이름을 스스로 바꾸는 Map' 을 만들었다. 그게 EgovMap 이다.
//  이 파일은 그 원리를 확인하기 위한 복제품이다.
// ============================================================
public class StudyMap extends LinkedHashMap<String, Object> {

    // ★ 부모(LinkedHashMap)의 put 을 '가로챈다'. 이게 오버라이드다.
    //   @Override 는 3일차에 인터페이스에서 봤는데, 상속에서도 똑같이 쓴다.
    @Override
    public Object put(String key, Object value) {
        // 부모의 put 을 부르는데, 키 이름만 바꿔서 넘긴다.
        // super = '부모'. super.put(...) = "부모가 원래 하던 put 을 해라"
        return super.put(toCamelCase(key), value);
    }

    // USE_YN -> useYn / use_yn -> useYn / name -> name
    private String toCamelCase(String column) {
        if (column == null) {
            return null;
        }
        // 밑줄이 없으면 그냥 소문자로만 (NAME -> name)
        if (column.indexOf('_') < 0) {
            return column.toLowerCase();
        }

        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : column.toLowerCase().toCharArray()) {
            if (c == '_') {
                upperNext = true;          // 밑줄을 만나면 '다음 글자를 대문자로'
                continue;                  // 밑줄 자체는 버린다
            }
            sb.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return sb.toString();
    }
}
