package com.oddsalchemist.backend.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

public class FetchRequest {

    @NotBlank(message = "URLは必須です。")
    @URL(message = "有効なURL形式で入力してください。")
    private String url;

    // Getter and Setter
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
