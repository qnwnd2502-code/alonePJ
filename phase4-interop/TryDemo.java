import java.io.IOException;

/**
 * try-with-resources 의 실행 순서를 눈으로 보는 파일.
 * 컴파일 없이 바로 실행된다:  java TryDemo.java   (자바 11 부터 가능)
 */
public class TryDemo {

    // AutoCloseable 을 implements 했기 때문에 try( ) 괄호 안에 들어갈 수 있다.
    // "나는 닫힐 줄 안다" 는 의무를 진 것이다.
    static class MyResource implements AutoCloseable {
        @Override
        public void close() {
            System.out.println("   [close]   자원이 닫혔다");
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 실행 순서를 번호가 아니라 '찍히는 차례'로 보세요 ===");

        try (MyResource r = new MyResource()) {
            System.out.println("   [try]     블록 안. 이제 예외를 던진다");
            throw new IOException("일부러 낸 예외");
        } catch (IOException e) {
            System.out.println("   [catch]   " + e.getMessage());
        } finally {
            System.out.println("   [finally] 마지막");
        }
    }
}
