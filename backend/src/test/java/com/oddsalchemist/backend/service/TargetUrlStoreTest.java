package com.oddsalchemist.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TargetUrlStoreTest {

    private GoogleSheetsService googleSheetsService;

    @BeforeEach
    void setUp() {
        googleSheetsService = mock(GoogleSheetsService.class);
    }

    @Test
    void loadFromSheet_起動時にSheetsからURLと実行時刻が復元されること() throws Exception {
        when(googleSheetsService.readData("Targets!A2:C")).thenReturn(List.of(
                List.of("https://example.com/race/1", "2026/03/12 10:00:00", "2026/03/12 10:30:00"),
                List.of("https://example.com/race/2", "", "")
        ));

        TargetUrlStore store = new TargetUrlStore(googleSheetsService);
        store.loadFromSheet();

        assertThat(store.getUrls()).containsExactlyInAnyOrder(
                "https://example.com/race/1",
                "https://example.com/race/2");
    }

    @Test
    void loadFromSheet_Sheetsが空の場合はURLリストが空であること() throws Exception {
        when(googleSheetsService.readData("Targets!A2:C")).thenReturn(List.of());

        TargetUrlStore store = new TargetUrlStore(googleSheetsService);
        store.loadFromSheet();

        assertThat(store.getUrls()).isEmpty();
    }

    @Test
    void loadFromSheet_Sheets読み込み失敗時でも起動が継続すること() throws Exception {
        when(googleSheetsService.readData("Targets!A2:C")).thenThrow(new IOException("API失敗"));

        TargetUrlStore store = new TargetUrlStore(googleSheetsService);
        // 例外がスローされないこと
        store.loadFromSheet();

        assertThat(store.getUrls()).isEmpty();
    }

    @Test
    void addUrl_登録時にSheetsへ書き込まれること() throws Exception {
        when(googleSheetsService.readData(any())).thenReturn(List.of());

        TargetUrlStore store = new TargetUrlStore(googleSheetsService);
        store.loadFromSheet();
        store.addUrl("https://example.com/race/1");

        verify(googleSheetsService).clearAndWriteData(eq("Targets!A2:C"), any());
    }

    @Test
    void addUrl_Sheets書き込み失敗時でもインメモリへの追加は確定すること() throws Exception {
        when(googleSheetsService.readData(any())).thenReturn(List.of());
        doThrow(new IOException("書き込み失敗")).when(googleSheetsService).clearAndWriteData(any(), any());

        TargetUrlStore store = new TargetUrlStore(googleSheetsService);
        store.loadFromSheet();
        boolean result = store.addUrl("https://example.com/race/1");

        assertThat(result).isTrue();
        assertThat(store.getUrls()).contains("https://example.com/race/1");
    }

    @Test
    void removeUrl_削除時にSheetsへ書き込まれること() throws Exception {
        when(googleSheetsService.readData(any())).thenReturn(List.of());

        TargetUrlStore store = new TargetUrlStore(googleSheetsService);
        store.loadFromSheet();
        store.addUrl("https://example.com/race/1");
        clearInvocations(googleSheetsService);

        store.removeUrl("https://example.com/race/1");

        verify(googleSheetsService).clearAndWriteData(eq("Targets!A2:C"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateExecutionTimes_時刻が更新されること() throws Exception {
        when(googleSheetsService.readData(any())).thenReturn(List.of());

        TargetUrlStore store = new TargetUrlStore(googleSheetsService);
        store.loadFromSheet();
        store.addUrl("https://example.com/race/1");

        store.updateExecutionTimes("https://example.com/race/1", "2026/03/12 10:00:00", "2026/03/12 10:05:00");

        // updateExecutionTimes は persistToSheet を呼ばないこと（addUrl 時の1回のみ）
        verify(googleSheetsService, times(1)).clearAndWriteData(any(), any());

        // persistToSheet を呼ぶと更新済みの時刻が書き込まれること
        ArgumentCaptor<List<List<Object>>> captor = ArgumentCaptor.forClass(List.class);
        store.persistToSheet();
        verify(googleSheetsService, times(2)).clearAndWriteData(eq("Targets!A2:C"), captor.capture());

        List<List<Object>> written = captor.getValue();
        assertThat(written).hasSize(1);
        assertThat(written.get(0)).containsExactly(
                "https://example.com/race/1", "2026/03/12 10:00:00", "2026/03/12 10:05:00");
    }
}
