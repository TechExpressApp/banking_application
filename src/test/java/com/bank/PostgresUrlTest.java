package com.bank;

import com.bank.db.PostgresUrl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgresUrlTest {

    @Test
    void parsesSupabasePoolerUrl() {
        PostgresUrl p = PostgresUrl.parse(
                "postgres://postgres.abcdefgh:p%40ss%3Aword@aws-0-us-east-1.pooler.supabase.com:6543/postgres?sslmode=require&supa=base-pooler.x");
        assertEquals("postgres.abcdefgh", p.user());
        assertEquals("p@ss:word", p.password());
        assertEquals("jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0&ApplicationName=banking-application", p.jdbcUrl());
        assertFalse(p.describe().contains("word"), "password must never be logged");
    }

    @Test
    void defaultsAndValidation() {
        PostgresUrl p = PostgresUrl.parse("postgresql://u:p@db.example.com/mydb");
        assertTrue(p.jdbcUrl().startsWith("jdbc:postgresql://db.example.com:5432/mydb?sslmode=require"));
        assertTrue(PostgresUrl.parse("postgresql://u:p@localhost:5432/x").jdbcUrl().contains("sslmode=disable"));
        assertThrows(IllegalArgumentException.class, () -> PostgresUrl.parse("mysql://u:p@h/db"));
        assertThrows(IllegalArgumentException.class, () -> PostgresUrl.parse("postgresql://h/db"));
    }
}
