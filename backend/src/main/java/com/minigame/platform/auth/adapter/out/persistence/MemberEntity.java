package com.minigame.platform.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "members")
public class MemberEntity {
    @Id
    private UUID id;

    @Column(name = "google_subject", nullable = false, unique = true, length = 128)
    private String googleSubject;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 40)
    private String nickname;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    protected MemberEntity() {
    }

    private MemberEntity(
            UUID id,
            String googleSubject,
            String email,
            String nickname,
            String avatarUrl,
            String status,
            Instant createdAt,
            Instant lastLoginAt
    ) {
        this.id = id;
        this.googleSubject = googleSubject;
        this.email = email;
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
        this.status = status;
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
    }

    public static MemberEntity create(
            UUID id,
            String googleSubject,
            String email,
            String nickname,
            String avatarUrl,
            Instant now
    ) {
        return new MemberEntity(id, googleSubject, email, nickname, avatarUrl, "ACTIVE", now, now);
    }

    public void recordGoogleLogin(String email, String nickname, String avatarUrl, Instant now) {
        this.email = email;
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
        this.lastLoginAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getGoogleSubject() {
        return googleSubject;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
