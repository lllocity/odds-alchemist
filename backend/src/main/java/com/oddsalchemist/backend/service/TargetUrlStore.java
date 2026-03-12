package com.oddsalchemist.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 監視対象URLをインメモリ＋Google Sheetsで管理するスレッドセーフなストア。
 * 起動時に Targets シートからURLを復元し、追加・削除のたびにシートへ永続化する。
 */
@Service
public class TargetUrlStore {

    private static final Logger logger = LoggerFactory.getLogger(TargetUrlStore.class);
    private static final String TARGETS_RANGE = "Targets!A2:C";

    /** URLごとの管理情報。lastExecutionTime / nextScheduledTime は null 許容（Sheetsからの復元時に値がない場合）。 */
    public record TargetUrlInfo(String url, String lastExecutionTime, String nextScheduledTime) {}

    private final ConcurrentHashMap<String, TargetUrlInfo> urlMap = new ConcurrentHashMap<>();
    private final GoogleSheetsService googleSheetsService;

    public TargetUrlStore(GoogleSheetsService googleSheetsService) {
        this.googleSheetsService = googleSheetsService;
    }

    /**
     * 起動時に Google Sheets の Targets シートからURLと実行時刻を復元します。
     * 読み込み失敗時は WARN ログのみ出力し、インメモリ空のまま起動を継続します。
     */
    @PostConstruct
    public void loadFromSheet() {
        try {
            List<List<Object>> rows = googleSheetsService.readData(TARGETS_RANGE);
            for (List<Object> row : rows) {
                if (row.isEmpty()) continue;
                String url = row.get(0).toString().trim();
                if (url.isBlank()) continue;
                if (urlMap.containsKey(url)) continue;
                String lastExec = row.size() > 1 ? row.get(1).toString().trim() : "";
                String nextSched = row.size() > 2 ? row.get(2).toString().trim() : "";
                urlMap.put(url, new TargetUrlInfo(
                        url,
                        lastExec.isBlank() ? null : lastExec,
                        nextSched.isBlank() ? null : nextSched));
                logger.info("SheetsからURL復元: {}", url);
            }
            logger.info("Sheets読み込み完了: {}件", urlMap.size());
        } catch (Exception e) {
            logger.warn("Sheetsからの読み込みに失敗しました。インメモリ空のまま起動を継続します", e);
        }
    }

    /**
     * 登録済みURLの一覧を返します（変更不可）。
     */
    public List<String> getUrls() {
        return Collections.unmodifiableList(new ArrayList<>(urlMap.keySet()));
    }

    /**
     * 登録済みURLの詳細情報一覧を返します（URL・実行時刻を含む）。
     */
    public List<TargetUrlInfo> getUrlInfos() {
        return Collections.unmodifiableList(new ArrayList<>(urlMap.values()));
    }

    /**
     * 指定URLの次回予定時刻を返します。未登録・未設定の場合は空を返します。
     *
     * @param url 対象URL
     * @return 次回予定時刻文字列（形式: yyyy/MM/dd HH:mm:ss）
     */
    public Optional<String> getNextScheduledTime(String url) {
        TargetUrlInfo info = urlMap.get(url);
        if (info == null || info.nextScheduledTime() == null || info.nextScheduledTime().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(info.nextScheduledTime());
    }

    /**
     * URLを監視対象に追加し、Sheetsへ永続化します。
     * 実行時刻は初回スクレイピング完了後に updateExecutionTimes() で設定されます。
     *
     * @return 追加に成功した場合 true、すでに登録済みの場合 false
     */
    public boolean addUrl(String url) {
        if (urlMap.containsKey(url)) {
            logger.warn("URLはすでに登録済みです: {}", url);
            return false;
        }
        urlMap.put(url, new TargetUrlInfo(url, null, null));
        logger.info("監視対象URLを追加: {}", url);
        persistToSheet();
        return true;
    }

    /**
     * URLを監視対象から削除し、Sheetsへ永続化します。
     *
     * @return 削除に成功した場合 true、対象が存在しない場合 false
     */
    public boolean removeUrl(String url) {
        TargetUrlInfo removed = urlMap.remove(url);
        if (removed != null) {
            logger.info("監視対象URLを削除: {}", url);
            persistToSheet();
            return true;
        }
        return false;
    }

    /**
     * URLの最終実行時間と次回予定時間を更新します。
     * Sheetsへの書き込みは行いません（呼び出し元が persistToSheet() を責務として持ちます）。
     *
     * @param url           対象URL
     * @param lastExecution 最終実行時間（形式: yyyy/MM/dd HH:mm:ss）
     * @param nextScheduled 次回予定時間（形式: yyyy/MM/dd HH:mm:ss）
     */
    public void updateExecutionTimes(String url, String lastExecution, String nextScheduled) {
        urlMap.computeIfPresent(url, (k, v) -> new TargetUrlInfo(url, lastExecution, nextScheduled));
    }

    /**
     * 現在のインメモリ状態を Targets シートへ全件上書き保存します。
     * 失敗時は ERROR ログのみ出力し、インメモリへの変更は確定済みとして扱います。
     */
    public void persistToSheet() {
        try {
            List<List<Object>> rows = new ArrayList<>();
            for (TargetUrlInfo info : urlMap.values()) {
                rows.add(List.of(
                        info.url(),
                        info.lastExecutionTime() != null ? info.lastExecutionTime() : "",
                        info.nextScheduledTime() != null ? info.nextScheduledTime() : ""));
            }
            googleSheetsService.clearAndWriteData(TARGETS_RANGE, rows);
        } catch (Exception e) {
            logger.error("Sheetsへの書き込みに失敗しました。インメモリへの変更は確定済みです", e);
        }
    }
}
