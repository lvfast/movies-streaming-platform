package com.lvfast.streaming.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCatalogRevisionRepository implements CatalogRevisionRepository {

    private final JdbcTemplate jdbc;

    JdbcCatalogRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long current() {
        Long revision = jdbc.queryForObject("select revision from catalog_revision where id=1", Long.class);
        return revision == null ? 0 : revision;
    }

    @Override
    public long bump() {
        Long revision = jdbc.queryForObject(
                "update catalog_revision set revision=revision+1 where id=1 returning revision", Long.class);
        return revision == null ? 0 : revision;
    }
}
