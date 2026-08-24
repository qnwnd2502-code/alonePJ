package egovframework.example.sample.web;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;

import egovframework.example.sample.service.EgovSampleService;

@Controller
public class EgovSampleController {

    @Resource(name = "sampleService")
    private EgovSampleService sampleService;

    @RequestMapping(value = "/egovSampleList.do")
    public String selectSampleList(Map<String, Object> commandMap, ModelMap model) throws Exception {

        List<Map<String, Object>> sampleList = sampleService.selectSampleList(commandMap);

        int useCount = 0;

        for (Map<String, Object> row : sampleList) {
            if ("Y".equals(row.get("USE_YN"))) {
                useCount = useCount + 1;
            }
        }

        model.addAttribute("resultList", sampleList);
        model.addAttribute("useCount", useCount);

        return "sample/egovSampleList";
    }
}

// ─────────────────────────────────────────────────────────────
//  읽기 연습용 파일이다. src/main/java 밖에 있으므로 컴파일되지 않는다.
//  실제 전자정부프레임워크 샘플 Controller 와 거의 같은 모양.
//
//  [3일차 해석 요약]
//   14  @Controller          -> 반환값을 '화면(JSP) 이름' 으로 해석 (@RestController 는 데이터)
//   12  import ...service.EgovSampleService
//                            -> 짝인 파일 위치가 여기 적혀 있다.
//                               목차 EgovSampleService.java + 본문 EgovSampleServiceImpl.java
//   17  @Resource(name="sampleService")
//                            -> '별명' 으로 찾아 꽂는다. 본문 파일엔 @Service("sampleService") 가 있다
//   20  @RequestMapping(".do") -> 주소 등록. 공공기관 주소가 .do 로 끝나는 이유
//   23  List<Map<String,Object>> -> DB 조회 결과 표 한 장 (Map 하나 = 행 하나)
//   25~31 int 계수기 + for + if -> USE_YN 이 "Y" 인 행의 '개수' 를 센다 (담는 게 아니라 세는 것)
//   33~34 model.addAttribute  -> JSP 로 넘길 짐칸에 싣는다
//   36  return "sample/egovSampleList" -> /WEB-INF/jsp/sample/egovSampleList.jsp
// ─────────────────────────────────────────────────────────────
