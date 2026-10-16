package com.multi.vidulum.shared.module;

/**
 * A socket: declared by one module, <b>implemented by another</b>.
 *
 * <p>The opposite direction to {@link ModuleApi}. {@code ExchangeAdapter} is declared in
 * {@code vidulum-exchange-api} and implemented by {@code vidulum-okx}; {@code BrokerQuotationProvider}
 * is declared in {@code vidulum-quotation-api} and implemented five times, once per venue. That is
 * what keeps the dependency pointing one way: quotation must stay unaware of OKX, so OKX reaches in
 * through the socket instead.
 *
 * <p>Marking it says which types are extension points, so that "who can plug into us, and where are
 * they" is answerable from the hierarchy. Like the other markers here, it carries no methods and is
 * never a variable type.
 */
public interface ModuleSpi {
}
