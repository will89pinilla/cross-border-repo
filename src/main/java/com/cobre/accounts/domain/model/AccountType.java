package com.cobre.accounts.domain.model;

/** Glossary §4. Only CLIENT accounts store a balance; Cobre accounts compute it from their lines (D10). */
public enum AccountType {
    CLIENT,
    COBRE_POSITION,
    COBRE_FUNDING
}
