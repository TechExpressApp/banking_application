package com.bank.db;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns a connection string as Supabase/Vercel give it ({@code postgres://user:pass@host:port/db?sslmode=require})
 * into a JDBC URL plus user and password. A {@code jdbc:postgresql://} URL is passed through as-is.
 */
public record PostgresUrl(String jdbcUrl, String user, String password, String host, int port, String database) {

    public static PostgresUrl parse(String raw) {
        String s = raw.trim();
        // Values copied from a .env file often keep their quotes.
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("jdbc:postgresql://")) return new PostgresUrl(s, null, null, "(jdbc url)", 0, "");
        if (!s.startsWith("postgres://") && !s.startsWith("postgresql://")) {
            throw new IllegalArgumentException("Database URL must start with postgresql:// or postgres://");
        }
        URI uri = URI.create(s.replaceFirst("^postgres(ql)?://", "http://"));
        String userInfo = uri.getRawUserInfo();
        if (userInfo == null || !userInfo.contains(":")) throw new IllegalArgumentException("Database URL has no user:password");
        String user = decode(userInfo.substring(0, userInfo.indexOf(':')));
        String password = decode(userInfo.substring(userInfo.indexOf(':') + 1));
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String db = uri.getPath() == null || uri.getPath().length() <= 1 ? "postgres" : uri.getPath().substring(1);

        String sslmode = "require";
        if (uri.getRawQuery() != null) {
            for (String kv : uri.getRawQuery().split("&")) {
                if (kv.startsWith("sslmode=")) sslmode = decode(kv.substring(8));
            }
        }
        if (uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1")) sslmode = "disable";
        // prepareThreshold=0: works through Supabase's transaction pooler, which doesn't support prepared statements.
        String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + db
                + "?sslmode=" + sslmode + "&prepareThreshold=0&ApplicationName=banking-application";
        return new PostgresUrl(jdbc, user, password, uri.getHost(), port, db);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** Safe to log: no password. */
    public String describe() {
        return host + ":" + port + "/" + database + " as " + user;
    }
}
