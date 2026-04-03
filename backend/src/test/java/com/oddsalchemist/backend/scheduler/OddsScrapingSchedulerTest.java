package com.oddsalchemist.backend.scheduler;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.service.OddsSyncService;
import com.oddsalchemist.backend.service.TargetUrlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OddsScrapingSchedulerTest {

    private OddsSyncService oddsSyncService;
    private TargetUrlStore targetUrlStore;
    private ScrapingProperties props;

    @BeforeEach
    void setUp() {
        oddsSyncService = mock(OddsSyncService.class);
        targetUrlStore = mock(TargetUrlStore.class);
        props = new ScrapingProperties("OddsData!A:H", 0);
    }

    // ===== scrapeAllTargets のテスト =====

    @Test
    void scrapeAllTargets_複数URLを順番に処理すること() throws Exception {
        when(targetUrlStore.getUrls()).thenReturn(
                List.of("https://example.com/race/1", "https://example.com/race/2"));
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        when(oddsSyncService.fetchAndSaveOdds("https://example.com/race/1", "OddsData!A:H")).thenReturn(10);
        when(oddsSyncService.fetchAndSaveOdds("https://example.com/race/2", "OddsData!A:H")).thenReturn(8);

        scheduler.scrapeAllTargets();

        verify(oddsSyncService).fetchAndSaveOdds("https://example.com/race/1", "OddsData!A:H");
        verify(oddsSyncService).fetchAndSaveOdds("https://example.com/race/2", "OddsData!A:H");
    }

    @Test
    void scrapeAllTargets_1件が失敗しても残りのURLを継続処理すること() throws Exception {
        when(targetUrlStore.getUrls()).thenReturn(
                List.of("https://example.com/race/fail", "https://example.com/race/ok"));
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        when(oddsSyncService.fetchAndSaveOdds("https://example.com/race/fail", "OddsData!A:H"))
                .thenThrow(new IOException("接続タイムアウト"));
        when(oddsSyncService.fetchAndSaveOdds("https://example.com/race/ok", "OddsData!A:H"))
                .thenReturn(5);

        // 例外がスローされないこと（システムを止めない）
        scheduler.scrapeAllTargets();

        verify(oddsSyncService).fetchAndSaveOdds("https://example.com/race/fail", "OddsData!A:H");
        verify(oddsSyncService).fetchAndSaveOdds("https://example.com/race/ok", "OddsData!A:H");
    }

    @Test
    void scrapeAllTargets_URLが0件の場合も正常に完了すること() {
        when(targetUrlStore.getUrls()).thenReturn(List.of());
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        scheduler.scrapeAllTargets();

        verifyNoInteractions(oddsSyncService);
    }

    // ===== calculateDelayForUrl の動的間隔テスト =====

    @Test
    void calculateDelayForUrl_発走時刻がキャッシュされていない場合は30分を返すこと() {
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        when(oddsSyncService.getCachedStartTime("https://example.com/race/1")).thenReturn(Optional.empty());

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(13, 0));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_30MIN);
    }

    @Test
    void calculateDelayForUrl_12時前は発走時刻によらず30分を返すこと() {
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        // 発走15:00, 現在11:00（正午前）→ 30分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(11, 0));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_30MIN);
    }

    @Test
    void calculateDelayForUrl_発走60分超前かつ12時以降は15分を返すこと() {
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        // 発走15:00, 現在13:00（残り120分）→ 5分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(13, 0));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_5MIN);
    }

    @Test
    void calculateDelayForUrl_発走60分前は1分を返すこと() {
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        // 発走15:00, 現在14:00（残り60分）→ 1分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(14, 0));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_1MIN);
    }

    @Test
    void calculateDelayForUrl_発走10分前は1分を返すこと() {
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        // 発走15:00, 現在14:55（残り5分）→ 1分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(14, 55));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_1MIN);
    }

    @Test
    void calculateDelayForUrl_発走後は30分を返すこと() {
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        // 発走15:00, 現在15:10（発走10分後）→ 30分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(15, 10));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_30MIN);
    }

    // ===== scheduleUrl / cancelUrl のテスト =====

    @Test
    void scheduleUrl_登録後にcancelUrlを呼んでも例外が発生しないこと() {
        String url = "https://example.com/race/1";
        when(oddsSyncService.getCachedStartTime(url)).thenReturn(Optional.empty());

        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);
        scheduler.start();
        try {
            scheduler.scheduleUrl(url);
            scheduler.cancelUrl(url);
        } finally {
            scheduler.stop();
        }
        // 例外が発生しなければテスト通過
    }

    @Test
    void cancelUrl_未登録URLでも例外が発生しないこと() {
        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);

        // start()なしでも cancelUrl は安全に動作すること
        scheduler.cancelUrl("https://example.com/race/unknown");

        // 例外が発生しなければテスト通過
    }

    // ===== restoreFromStore のテスト =====

    @Test
    void restoreFromStore_次回予定時刻が未設定の場合は即時フェッチが開始されること() throws Exception {
        String url = "https://example.com/race/1";
        when(targetUrlStore.getUrls()).thenReturn(List.of(url));
        when(targetUrlStore.getNextScheduledTime(url)).thenReturn(Optional.empty());
        when(oddsSyncService.fetchAndSaveOdds(eq(url), any())).thenReturn(5);
        when(oddsSyncService.getCachedStartTime(url)).thenReturn(Optional.empty());

        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);
        scheduler.start();
        try {
            scheduler.restoreFromStore();
            verify(oddsSyncService, timeout(2000)).fetchAndSaveOdds(url, "OddsData!A:H");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void restoreFromStore_次回予定時刻が未来の場合は即時フェッチしないこと() throws Exception {
        String url = "https://example.com/race/1";
        String futureTime = LocalDateTime.now().plusMinutes(30)
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        when(targetUrlStore.getUrls()).thenReturn(List.of(url));
        when(targetUrlStore.getNextScheduledTime(url)).thenReturn(Optional.of(futureTime));

        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);
        scheduler.start();
        try {
            scheduler.restoreFromStore();
            // 即時fetchは呼ばれないこと（予定時刻まで待機）
            verifyNoInteractions(oddsSyncService);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void restoreFromStore_URLが0件の場合はスクレイピングされないこと() {
        when(targetUrlStore.getUrls()).thenReturn(List.of());

        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);
        scheduler.restoreFromStore();

        verifyNoInteractions(oddsSyncService);
    }

    // ===== scrapeAndReschedule のテスト =====

    @Test
    void scrapeAndReschedule_完了後にupdateExecutionTimesとpersistToSheetが呼ばれること() throws Exception {
        String url = "https://example.com/race/1";
        when(targetUrlStore.containsUrl(url)).thenReturn(true);
        when(oddsSyncService.fetchAndSaveOdds(eq(url), any())).thenReturn(3);
        when(oddsSyncService.getCachedStartTime(url)).thenReturn(Optional.empty());

        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);
        scheduler.start();
        try {
            scheduler.scrapeAndReschedule(url);
            verify(targetUrlStore).updateExecutionTimes(eq(url), anyString(), anyString());
            verify(targetUrlStore).persistToSheet();
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void updateAndPersistExecutionTimes_updateExecutionTimesとpersistToSheetが呼ばれること() {
        String url = "https://example.com/race/1";
        when(oddsSyncService.getCachedStartTime(url)).thenReturn(Optional.empty());

        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, props, targetUrlStore);
        scheduler.updateAndPersistExecutionTimes(url);

        verify(targetUrlStore).updateExecutionTimes(eq(url), anyString(), anyString());
        verify(targetUrlStore).persistToSheet();
    }

    @Test
    void scheduleUrl_デバッグ間隔が設定されている場合は固定間隔でスケジュールされること() {
        String url = "https://example.com/race/1";
        ScrapingProperties debugProps = new ScrapingProperties("OddsData!A:H", 2);
        when(oddsSyncService.getCachedStartTime(url)).thenReturn(Optional.empty());

        OddsScrapingScheduler scheduler = new OddsScrapingScheduler(oddsSyncService, debugProps, targetUrlStore);
        scheduler.start();
        try {
            // デバッグ間隔2分の場合、calculateDelayForUrl を使わずに固定2分でスケジュール
            scheduler.scheduleUrl(url);
        } finally {
            scheduler.stop();
        }

        // getCachedStartTime は呼ばれない（デバッグ間隔を直接使うため）
        verify(oddsSyncService, never()).getCachedStartTime(any());
    }

}
