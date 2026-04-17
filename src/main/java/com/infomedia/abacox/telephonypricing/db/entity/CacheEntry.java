package com.infomedia.abacox.telephonypricing.db.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(name = "cache_entry",
        indexes = {
                @Index(name = "idx_cache_entry_expires_at", columnList = "expires_at"),
                @Index(name = "idx_cache_entry_cache_name", columnList = "cache_name")
        })
@IdClass(CacheEntry.CacheEntryId.class)
public class CacheEntry {

    @Id
    @Column(name = "cache_name", nullable = false, length = 100)
    private String cacheName;

    @Id
    @Column(name = "cache_key", nullable = false, length = 1024)
    private String cacheKey;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "type_info", length = 500)
    private String typeInfo;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheEntryId implements Serializable {
        private String cacheName;
        private String cacheKey;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheEntryId that)) return false;
            return Objects.equals(cacheName, that.cacheName)
                    && Objects.equals(cacheKey, that.cacheKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cacheName, cacheKey);
        }
    }
}
