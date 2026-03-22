package com.oddsalchemist.backend.util;

import java.time.format.DateTimeFormatter;

/**
 * Sheetsへの書き込み・読み込みに使用する日時フォーマットの共通定数。
 * OddsData / Alerts / Targets のすべてのシートで統一する。
 */
public final class SheetsDates {

    /** Sheetsの日時文字列フォーマット（例: "2026/03/22 11:56:54"） */
    public static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private SheetsDates() {}
}
