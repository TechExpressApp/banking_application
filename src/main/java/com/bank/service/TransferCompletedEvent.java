package com.bank.service;

import com.bank.model.Transaction;

/** Published after a transfer has been saved. The fraud monitor agent listens for it. */
public record TransferCompletedEvent(Transaction transaction) {}
