package com.sawoo.gametranslator;

import com.sawoo.gametranslator.core.GameProject;

import java.io.File;

/** 화면과 백그라운드 작업이 함께 보는 현재 작업 상태. */
final class Job {
    enum Stage { IDLE, ANALYZING, READY, TRANSLATING, BUILDING, DONE, ERROR }

    static volatile Stage stage = Stage.IDLE;
    static volatile int done, total;
    static volatile String message = "";
    static volatile String error = "";
    static volatile boolean cancel;
    static volatile GameProject project;
    static volatile File output;
    static volatile int translated, failed;

    private Job() {}

    static final GameProject.Progress PROGRESS = new GameProject.Progress() {
        @Override public void update(int d, int t, String m) {
            done = d;
            total = t;
            message = m;
        }
        @Override public boolean cancelled() { return cancel; }
    };

    static boolean busy() {
        return stage == Stage.ANALYZING || stage == Stage.TRANSLATING || stage == Stage.BUILDING;
    }
}
