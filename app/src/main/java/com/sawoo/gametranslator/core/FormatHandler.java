package com.sawoo.gametranslator.core;

import java.util.function.UnaryOperator;

/**
 * 파일 형식별 처리기. 문자열 수집과 번역 적용이 같은 코드를 지나가므로
 * 수집한 문자열과 적용되는 문자열이 어긋나지 않는다.
 */
public interface FormatHandler {
    String label();

    /** @param fn 원문을 받아 결과 문자열을 돌려주는 함수 */
    byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) throws Exception;

    final class Context {
        public final String path;
        public final Lang lang;
        /** 한글 글꼴을 함께 넣을 때 렌파이 글꼴 설정을 바꿀 파일 이름. 없으면 null. */
        public final String koreanFont;

        public Context(String path, Lang lang, String koreanFont) {
            this.path = path;
            this.lang = lang;
            this.koreanFont = koreanFont;
        }
    }
}
