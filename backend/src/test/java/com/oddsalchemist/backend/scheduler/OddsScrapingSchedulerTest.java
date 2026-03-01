package com.oddsalchemist.backend.scheduler;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.service.OddsSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OddsScrapingSchedulerTest {

    private OddsSyncService oddsSyncService;
    private OddsScrapingScheduler scheduler;

    @BeforeEach
    void setUp() {
        oddsSyncService = mock(OddsSyncService.class);
    }

    // ===== scrapeAllTargets のテスト =====

    @Test
    void scrapeAllTargets_複数URLを順番に処理すること() throws Exception {
        ScrapingProperties props = new ScrapingProperties(
                "シート1!A:G",
                List.of("https://example.com/race/1", "https://example.com/race/2")
        );
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        when(oddsSyncService.fetchAndSaveOdds("https://example.com/race/1", "シート1!A:G")).thenReturn(10);
        when(oddsSyncService.fetchAndSaveOdds("https://example.com/race/2", "シート1!A:G")).thenReturn(8);

        scheduler.scrapeAllTargets();

        verify(oddsSyncService).fetchAndSaveOdds("https://example.com/race/1", "シート1!A:G");
        verify(oddsSyncService).fetchAndSaveOdds("https://example.com/race/2", "シート1!A:G");
    }

    @Test
    void scrapeAllTargets_1件が失敗しても残りのURLを継続処理すること() throws Exception {
        ScrapingProperties props = new ScrapingProperties(
                "シート1!A:G",
                List.of("https://example.com/race/fail", "https://example.com/race/ok")
        );
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        when(oddsSyncService.fetchAndSaveOdds("https://example.com/race/fail", "シート1!A:G"))
                .thenThrow(new IOException("接続タイムアウト"));
        when(oddsSyncService.fetchAndSaveOdds("https://example.com/race/ok", "シート1!A:G"))
                .thenReturn(5);

        // 例外がスローされないこと（システムを止めない）
        scheduler.scrapeAllTargets();

        verify(oddsSyncService).fetchAndSaveOdds("https://example.com/race/fail", "シート1!A:G");
        verify(oddsSyncService).fetchAndSaveOdds("https://example.com/race/ok", "シート1!A:G");
    }

    @Test
    void scrapeAllTargets_URLが0件の場合も正常に完了すること() {
        ScrapingProperties props = new ScrapingProperties(
                "シート1!A:G",
                List.of()
        );
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        scheduler.scrapeAllTargets();

        verifyNoInteractions(oddsSyncService);
    }

    // ===== calculateDelayForUrl の動的間隔テスト =====

    @Test
    void calculateDelayForUrl_発走時刻がキャッシュされていない場合は30分を返すこと() {
        ScrapingProperties props = new ScrapingProperties("シート1!A:G", List.of("https://example.com/race/1"));
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        when(oddsSyncService.getCachedStartTime("https://example.com/race/1")).thenReturn(Optional.empty());

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(13, 0));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_30MIN);
    }

    @Test
    void calculateDelayForUrl_12時前は発走時刻によらず30分を返すこと() {
        ScrapingProperties props = new ScrapingProperties("シート1!A:G", List.of("https://example.com/race/1"));
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        // 発走15:00, 現在11:00（正午前）→ 30分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(11, 0));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_30MIN);
    }

    @Test
    void calculateDelayForUrl_発走60分超前かつ12時以降は15分を返すこと() {
        ScrapingProperties props = new ScrapingProperties("シート1!A:G", List.of("https://example.com/race/1"));
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        // 発走15:00, 現在13:00（残り120分）→ 15分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(13, 0));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_15MIN);
    }

    @Test
    void calculateDelayForUrl_発走60分前は5分を返すこと() {
        ScrapingProperties props = new ScrapingProperties("シート1!A:G", List.of("https://example.com/race/1"));
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        // 発走15:00, 現在14:00（残り60分）→ 5分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(14, 0));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_5MIN);
    }

    @Test
    void calculateDelayForUrl_発走10分前は1分を返すこと() {
        ScrapingProperties props = new ScrapingProperties("シート1!A:G", List.of("https://example.com/race/1"));
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        // 発走15:00, 現在14:55（残り5分）→ 1分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(14, 55));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_1MIN);
    }

    @Test
    void calculateDelayForUrl_発走後は30分を返すこと() {
        ScrapingProperties props = new ScrapingProperties("シート1!A:G", List.of("https://example.com/race/1"));
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        // 発走15:00, 現在15:10（発走10分後）→ 30分
        when(oddsSyncService.getCachedStartTime("https://example.com/race/1"))
                .thenReturn(Optional.of(LocalTime.of(15, 0)));

        Duration delay = scheduler.calculateDelayForUrl("https://example.com/race/1", LocalTime.of(15, 10));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_30MIN);
    }

    @Test
    void calculateDelay_複数URLのうち最短の遅延が選択されること() {
        String url1 = "https://example.com/race/1";
        String url2 = "https://example.com/race/2";
        ScrapingProperties props = new ScrapingProperties("シート1!A:G", List.of(url1, url2));
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        // URL1: 発走15:00, 現在14:55 → 残り5分 → 1分間隔
        // URL2: 発走17:00, 現在14:55 → 残り125分 → 15分間隔
        // → 最小 = 1分
        when(oddsSyncService.getCachedStartTime(url1)).thenReturn(Optional.of(LocalTime.of(15, 0)));
        when(oddsSyncService.getCachedStartTime(url2)).thenReturn(Optional.of(LocalTime.of(17, 0)));

        Duration delay = scheduler.calculateDelay(LocalTime.of(14, 55));

        assertThat(delay).isEqualTo(OddsScrapingScheduler.DELAY_1MIN);
    }
}
