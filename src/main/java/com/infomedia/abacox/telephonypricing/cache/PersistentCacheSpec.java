package com.infomedia.abacox.telephonypricing.cache;

import java.time.Duration;

public record PersistentCacheSpec(String name, Duration ttl, long maxSize) {
}
