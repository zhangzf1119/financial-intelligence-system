package com.rpa.financial_intelligence_system.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class UserPrincipalService implements UserDetailsService {
    private final JdbcClient jdbc;
    public UserPrincipalService(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public UserDetails loadUserByUsername(String username) {
        var row = jdbc.sql("SELECT username,password,status FROM sys_user WHERE username=:u").param("u", username).query().singleRow();
        var authorities = jdbc.sql("""
            SELECT DISTINCT x.authority FROM (
              SELECT 'ROLE_'||r.code authority FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id JOIN sys_user u ON u.id=ur.user_id WHERE u.username=:u AND r.status
              UNION SELECT m.permission FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id=m.id JOIN sys_user_role ur ON ur.role_id=rm.role_id JOIN sys_user u ON u.id=ur.user_id WHERE u.username=:u AND m.permission IS NOT NULL
            ) x""").param("u", username).query(String.class).list().stream().map(SimpleGrantedAuthority::new).toList();
        return User.withUsername(username).password((String) row.get("password")).disabled(!((Boolean)row.get("status"))).authorities(authorities).build();
    }
}
