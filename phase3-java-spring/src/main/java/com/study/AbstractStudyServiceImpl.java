package com.study;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ============================================================
//  전자정부의 EgovAbstractServiceImpl 을 흉내 낸 '부모' 클래스.
//
//  회사 소스의 ServiceImpl 첫 줄이 왜 이렇게 생겼는지 알기 위한 파일이다:
//      public class EgovSampleServiceImpl
//              extends EgovAbstractServiceImpl        <- 오늘 배우는 것
//              implements EgovSampleService {         <- 3일차에 배운 것
//
//  ★ abstract = '반제품'. 이 클래스는 혼자서 new 할 수 없다.
//    누군가 extends 로 물려받아야만 쓸 수 있다.
//    -> new AbstractStudyServiceImpl() 을 쓰면 컴파일 에러가 난다
// ============================================================
public abstract class AbstractStudyServiceImpl {

    // ★ protected = '자식만 쓸 수 있다'.
    //   private 이면 자식도 못 본다. public 이면 아무나 본다. 그 중간.
    //   전자정부의 EgovAbstractServiceImpl 에도 이런 로거 필드가 들어있다.
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    // ------------------------------------------------------------
    //  물려주는 기능 1) 로그 남기기
    //  전자정부 실물 이름은 leaveaTrace(String message) 다.
    //  (leave a trace = 흔적을 남긴다. 오타처럼 붙어있는 게 실제 이름이다)
    // ------------------------------------------------------------
    protected void leaveaTrace(String message) {
        logger.info("[TRACE] {}", message);
    }

    // ------------------------------------------------------------
    //  물려주는 기능 2) 예외를 회사 표준 모양으로 바꿔 던지기
    //  전자정부 실물 이름은 processException(...) 이다.
    //  회사 ServiceImpl 이 try-catch 안에서 이걸 부른다.
    // ------------------------------------------------------------
    protected void processException(String message, Exception cause) {
        logger.error("[ERROR] {}", message, cause);
        throw new RuntimeException(message, cause);
    }
}
