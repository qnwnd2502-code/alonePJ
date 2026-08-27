<%@ page contentType="text/html; charset=utf-8" pageEncoding="utf-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<html>
<head><title>샘플 목록</title></head>
<body>

  <h2>총 사용중: ${useCount} 건</h2>

  <table>
    <tr><th>ID</th><th>이름</th><th>사용여부</th></tr>

    <c:forEach var="row" items="${sampleList}">
      <tr>
        <td>${row.SAMPLE_ID}</td>
        <td>${row.NAME}</td>
        <td>${row.USE_YN}</td>
      </tr>
    </c:forEach>
  </table>

</body>
</html>
