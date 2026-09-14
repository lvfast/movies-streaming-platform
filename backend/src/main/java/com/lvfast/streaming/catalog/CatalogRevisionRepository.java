package com.lvfast.streaming.catalog;

/** Single-row PostgreSQL catalog revision used to namespace Redis cache keys. */
public interface CatalogRevisionRepository {

    long current();

    long bump();
}
