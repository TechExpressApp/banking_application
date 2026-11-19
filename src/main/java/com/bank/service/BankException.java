package com.bank.service;

import org.springframework.http.HttpStatus;

public class BankException extends RuntimeException {
    private final HttpStatus status;

    public BankException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static BankException badRequest(String msg) { return new BankException(HttpStatus.BAD_REQUEST, msg); }
    public static BankException notFound(String msg) { return new BankException(HttpStatus.NOT_FOUND, msg); }
    public static BankException unauthorized(String msg) { return new BankException(HttpStatus.UNAUTHORIZED, msg); }
    public static BankException forbidden(String msg) { return new BankException(HttpStatus.FORBIDDEN, msg); }
    public static BankException conflict(String msg) { return new BankException(HttpStatus.CONFLICT, msg); }
}
