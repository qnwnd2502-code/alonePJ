package com.study.interop;

import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 브라우저에서 눌러볼 수 있게 만든 실습용 창구.
// 실제 회사 코드에서는 Controller 가 아니라 배치나 화면 로직이 Client 를 부른다.
@RestController
@RequestMapping("/interop")
public class InteropController {

    @Resource
    private PartnerClient partnerClient;

    // 1) URL 에 키를 붙여 호출 (공공데이터포털 방식)
    @GetMapping("/list-urlkey")
    public Map<String, Object> listByUrlKey() {
        return partnerClient.fileListByUrlKey();
    }

    // 2) 헤더에 키를 실어 호출 (권장 방식)
    @GetMapping("/list-headerkey")
    public Map<String, Object> listByHeaderKey() {
        return partnerClient.fileListByHeaderKey();
    }

    // 3) 키 없이 호출 -> 401. 실패를 어떻게 분류하는지 본다
    @GetMapping("/list-nokey")
    public Map<String, Object> listWithoutKey() {
        return partnerClient.fileListWithoutKey();
    }

    // ============================================================
    //  ★ 4) "DB 에 파일 경로가 있으면 그걸로 열면 되지 않나?" 를 직접 확인한다 ★
    //
    //  상대 기관이 내려준 fileStreCours + streFileNm 을 그대로 File 로 열어본다.
    //  경로 문자열은 분명히 손에 있는데 파일이 없다.
    //  그 경로는 '상대 서버의' 경로이고, 우리 서버에는 그런 폴더가 없기 때문이다.
    //
    //  -> DB 에는 '주소' 만 있고 '물건' 은 없다.
    //     물건을 옮기려면 별도 수단(API 다운로드 / SFTP)이 필요하다.
    // ============================================================
    @SuppressWarnings("unchecked")
    @GetMapping("/open-by-path")
    public List<Map<String, Object>> openByPath() {

        Map<String, Object> body = partnerClient.fileListByHeaderKey();
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");

        return items.stream().map(row -> {
            String path = row.get("fileStreCours") + "" + row.get("streFileNm");
            File f = new File(path);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("원래파일명", row.get("orignlFileNm"));
            r.put("DB가알려준경로", path);
            r.put("우리서버에존재하나", f.exists());          // false 가 나온다
            r.put("읽을수있나", f.canRead());                 // false
            r.put("설명", f.exists()
                    ? "있다 (같은 서버이거나 공유 스토리지를 마운트한 경우)"
                    : "없다. 이 경로는 상대 서버의 경로다. 우리 서버에는 그런 폴더가 없다");
            return r;
        }).toList();
    }

    // 5) 제대로 가져오는 법: 상대가 열어준 다운로드 창구를 쓴다
    @GetMapping("/download")
    public Map<String, Object> download(@RequestParam String atchFileId) {

        byte[] bytes = partnerClient.downloadFile(atchFileId);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("atchFileId", atchFileId);
        r.put("받은바이트수", bytes.length);
        // 실습용이라 내용을 글자로 보여준다. 실제로는 파일로 저장하거나 DB에 넣는다
        r.put("내용", new String(bytes, StandardCharsets.UTF_8));
        r.put("설명", "HTTP 로 바이너리를 받아왔다. 경로가 아니라 실물이다");
        return r;
    }
}
