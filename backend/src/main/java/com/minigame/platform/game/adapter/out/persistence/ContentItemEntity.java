package com.minigame.platform.game.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "content_items")
public class ContentItemEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pack_id", nullable = false)
    private ContentPackEntity contentPack;

    @Column(nullable = false)
    private String value;

    @Column(name = "normalized_value", nullable = false)
    private String normalizedValue;

    @Column(nullable = false)
    private boolean active;

    protected ContentItemEntity() {
    }

    public UUID getId() {
        return id;
    }

    public ContentPackEntity getContentPack() {
        return contentPack;
    }

    public String getValue() {
        return value;
    }

    public boolean isActive() {
        return active;
    }
}
