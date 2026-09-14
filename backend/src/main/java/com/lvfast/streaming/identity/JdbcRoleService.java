package com.lvfast.streaming.identity;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRoleService implements RoleService {

    private static final String ADMIN = "ADMIN";

    private final JdbcTemplate jdbc;

    JdbcRoleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> roles(UUID userId) {
        return new LinkedHashSet<>(
                jdbc.queryForList("select role from user_role where user_id=? order by role", String.class, userId));
    }

    @Override
    public boolean isAdmin(UUID userId) {
        Boolean admin = jdbc.queryForObject(
                "select exists(select 1 from user_role where user_id=? and role=?)", Boolean.class, userId, ADMIN);
        return Boolean.TRUE.equals(admin);
    }

    @Override
    public void grant(UUID userId, String role) {
        jdbc.update("insert into user_role(user_id, role) values (?, ?) on conflict (user_id, role) do nothing",
                userId, role);
    }

    @Override
    public void revoke(UUID userId, String role) {
        jdbc.update("delete from user_role where user_id=? and role=?", userId, role);
    }

    @Override
    public long countAdmins() {
        Long count = jdbc.queryForObject("select count(*) from user_role where role=?", Long.class, ADMIN);
        return count == null ? 0 : count;
    }
}
