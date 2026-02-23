package com.oddsalchemist.backend.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Configuration
public class GoogleSheetsConfig {

    private static final String APPLICATION_NAME = "Odds Alchemist";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String CREDENTIALS_FILE_PATH = "credentials.json";

    @Bean
    public Sheets getSheetsService() throws IOException, GeneralSecurityException {
        // JSONキーを読み込み、スプレッドシートへのアクセス権限（スコープ）を設定
        ClassPathResource resource = new ClassPathResource(CREDENTIALS_FILE_PATH);
        if (!resource.exists()) {
            throw new IOException("Google Sheets認証ファイルが見つかりません。src/main/resources/" + CREDENTIALS_FILE_PATH + " に配置してください。");
        }

        try (InputStream in = resource.getInputStream()) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                    .createScoped(Collections.singletonList(SheetsScopes.SPREADSHEETS));

            // 認証済みのSheetsクライアントを構築して返す
            return new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JSON_FACTORY,
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        }
    }
}