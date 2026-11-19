package com.sawoo.gametranslator.core;

/** 번역할 원문 언어. */
public enum Lang {
    ENGLISH("en", "영어", "English"),
    JAPANESE("ja", "일본어", "Japanese"),
    CHINESE("zh", "중국어", "Chinese");

    public final String code;
    public final String label;
    public final String english;

    Lang(String code, String label, String english) {
        this.code = code;
        this.label = label;
        this.english = english;
    }
}
