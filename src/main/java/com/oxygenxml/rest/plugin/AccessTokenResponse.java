package com.oxygenxml.rest.plugin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalUnit;

public class AccessTokenResponse {
    public String access_token;
    public String token_type;
    public Integer expires_in;
    public String refresh_token;

    public LocalDateTime issueTime = LocalDateTime.MAX;

    public LocalDateTime getAccessTokenExpireTime() {
        if (issueTime == LocalDateTime.MAX)
            return LocalDateTime.now().plusSeconds(-60);

        return this.issueTime.plusSeconds(expires_in - 10);
    }

    public LocalDateTime getRefreshTokenExpireTime() {

        if (issueTime == LocalDateTime.MAX)
            return LocalDateTime.now().plusSeconds(-60);

        // refresh token from CMS last 24 hours
        // the information is not provided in the token oject, we just have to
        // hard code it
        return issueTime.plusHours(22);
    }
}