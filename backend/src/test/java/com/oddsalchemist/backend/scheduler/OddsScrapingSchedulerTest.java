package com.oddsalchemist.backend.scheduler;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.service.OddsSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.*;

class OddsScrapingSchedulerTest {

    private OddsSyncService oddsSyncService;
    private OddsScrapingScheduler scheduler;

    @BeforeEach
    void setUp() {
        oddsSyncService = mock(OddsSyncService.class);
    }

    @Test
    void scrapeAllTargets_複数URLを順番に処理すること() throws Exception {
        ScrapingProperties props = new ScrapingProperties(
                "0 */5 * * * *",
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
                "0 */5 * * * *",
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
                "0 */5 * * * *",
                "シート1!A:G",
                List.of()
        );
        scheduler = new OddsScrapingScheduler(oddsSyncService, props);

        scheduler.scrapeAllTargets();

        verifyNoInteractions(oddsSyncService);
    }
}
