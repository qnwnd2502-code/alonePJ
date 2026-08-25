package com.study;

// ============================================
//  VO = Value Object = '값을 담는 도시락통'
//  하는 일이 없다. 데이터를 담고 꺼내주는 게 전부다.
//  전자정부 소스의 ○○VO.java / ○○DTO.java 가 전부 이 모양이다.
// ============================================
public class MemberVO {

    // 통 안의 칸들. private = 밖에서 직접 못 만진다.
    // ★ 칸 이름이 DB 컬럼 이름과 짝이다 (member_id -> memberId).
    //   밑줄->낙타등 변환은 application.properties 의
    //   mybatis.configuration.map-underscore-to-camel-case=true 가 해준다.
    private Integer memberId;   // member_id
    private String  name;       // name
    private String  useYn;      // use_yn
    private String  regDt;      // reg_dt

    // getter = 꺼내는 문. get + 칸이름(첫글자 대문자)
    public Integer getMemberId() { return memberId; }
    public String  getName()     { return name; }
    public String  getUseYn()    { return useYn; }
    public String  getRegDt()    { return regDt; }

    // setter = 넣는 문. set + 칸이름
    // ★ MyBatis 는 SQL 결과를 이 setter 를 호출해서 통에 담는다.
    //   setter 이름이 안 맞으면 그 칸만 조용히 null 이 된다.
    public void setMemberId(Integer memberId) { this.memberId = memberId; }
    public void setName(String name)          { this.name = name; }
    public void setUseYn(String useYn)        { this.useYn = useYn; }
    public void setRegDt(String regDt)        { this.regDt = regDt; }
}
