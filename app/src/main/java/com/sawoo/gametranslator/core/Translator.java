package com.sawoo.gametranslator.core;

import java.util.List;

/** 번역 엔진. */
public interface Translator {
    /** true 면 보호 토큰(⟦0⟧)을 유지할 수 있는 엔진이고, false 면 태그 사이 조각만 받는다. */
    boolean keepsTokens();

    int maxBatchSize();

    int maxBatchChars();

    /** 동시에 보낼 수 있는 묶음 수. translate 는 이 수만큼 여러 스레드에서 동시에 불린다. */
    default int parallelism() { return 1; }

    /** 입력과 같은 길이의 목록을 돌려준다. 실패한 항목은 null. */
    List<String> translate(List<String> texts) throws Exception;

    default void close() {}
}
