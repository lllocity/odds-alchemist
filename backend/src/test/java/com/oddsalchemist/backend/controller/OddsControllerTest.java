package com.oddsalchemist.backend.controller;

import com.oddsalchemist.backend.config.ScrapingProperties;
import com.oddsalchemist.backend.service.GoogleSheetsService;
import com.oddsalchemist.backend.service.OddsSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OddsControllerTest {

    private GoogleSheetsService googleSheetsService;
    private OddsController controller;

    @BeforeEach
    void setUp() {
        googleSheetsService = mock(GoogleSheetsService.class);
        controller = new OddsController(
                mock(OddsSyncService.class),
                mock(ScrapingProperties.class),
                googleSheetsService
        );
    }

    // ===== clearSheet =====

    @Test
    void clearSheet_OddsDataを指定すると200が返されること() throws IOException {
        ResponseEntity<?> response = controller.clearSheet("OddsData");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).get("message").toString())
                .contains("OddsData");
        verify(googleSheetsService).clearAndWriteData(eq("OddsData!A2:H"), eq(List.of()));
    }

    @Test
    void clearSheet_Alertsを指定すると200が返されること() throws IOException {
        ResponseEntity<?> response = controller.clearSheet("Alerts");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).get("message").toString())
                .contains("Alerts");
        verify(googleSheetsService).clearAndWriteData(eq("Alerts!A2:G"), eq(List.of()));
    }

    @Test
    void clearSheet_不正なシート名を指定すると400が返されること() throws IOException {
        ResponseEntity<?> response = controller.clearSheet("Unknown");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(googleSheetsService);
    }

    @Test
    void clearSheet_Sheets_API例外発生時は500が返されること() throws IOException {
        doThrow(new IOException("API error"))
                .when(googleSheetsService).clearAndWriteData(any(), any());

        ResponseEntity<?> response = controller.clearSheet("OddsData");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(((Map<?, ?>) response.getBody()).get("message").toString())
                .contains("クリアに失敗しました");
    }
}
