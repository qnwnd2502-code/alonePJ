<%--
  회사에서 쓰는 그 JSP다. <% %> 안은 자바 코드고, 밖은 HTML이다.
  이 파일은 톰캣이 켜질 때 자바로 번역돼서 컴파일된다.
  (그래서 JSP를 처음 열 때만 유난히 느리다 -- 실무에서 "첫 화면이 느려요"의 정체)
--%>
<%@ page contentType="text/html; charset=UTF-8" %>
<%@ page import="java.net.InetAddress, java.util.Date" %>
<%
    // 이 코드를 지금 실행하고 있는 톰캣이 누구인지
    String here = InetAddress.getLocalHost().getHostName();

    // session 은 우리가 선언한 적 없는데 그냥 쓸 수 있다.
    // JSP가 미리 만들어주는 '내장 객체'이고, 정체는 HttpSession 이다.
    // 우리가 파이썬에서 손으로 만든 SESSIONS = {} 딕셔너리를, 자바는 이렇게 공짜로 준다.
    // 편한 대신, 그게 '이 톰캣의 메모리 안'이라는 사실이 가려진다 -- 오늘의 함정.
    Integer count = (Integer) session.getAttribute("count");
    count = (count == null) ? 1 : count + 1;
    session.setAttribute("count", count);
%>
<!doctype html>
<html lang="ko">
<head><meta charset="UTF-8"><title>Tomcat 세션 확인</title></head>
<body style="font-family:sans-serif; font-size:18px; line-height:2">
  <h2>Tomcat 세션 확인</h2>
  <p>응답한 톰캣 : <b><%= here %></b></p>
  <p>JSESSIONID  : <code><%= session.getId() %></code></p>
  <p>세션 생성 시각 : <%= new Date(session.getCreationTime()) %></p>
  <p style="font-size:28px">이 세션으로 방문한 횟수 : <b><%= count %></b></p>
  <hr>
  <p><a href="/static/hello.txt">/static/hello.txt</a> — 이건 아파치가 직접 준다(톰캣까지 안 옴)</p>
</body>
</html>
