package com.multi.vidulum.cashflow.api;

/**
 * Which way the money goes.
 *
 * <p>In the contract rather than in cashflow's domain because it is the most widely shared thing
 * cashflow has: 95 files name it, 39 of them in bank-data-ingestion alone, four of those being
 * Mongo entities - so it is part of that subdomain's stored shape as much as of cashflow's. A
 * vocabulary that many callers depend on is a contract, whatever package it started in.
 *
 * <p>Moving it does not touch stored data: Spring Data Mongo and Jackson both write an enum by its
 * constant name, and nothing here carries a {@code _class} discriminator or {@code @JsonTypeInfo}.
 * {@code INFLOW} stays {@code INFLOW} in every document and on every topic.
 */
public enum Type {
    INFLOW, OUTFLOW
}
