package com.multi.vidulum.quotation.api;

import com.multi.vidulum.shared.module.ModuleApi;
import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;

/**
 * What other modules may ask the quotation module.
 *
 * <p>Three methods, because three is what anybody outside actually calls. Everything that makes
 * answering hard — the pivot through the dollar, the central bank fixing tried before the chain,
 * USDT standing in for USD, a currency against itself being one — is behind this and stays there.
 *
 * <p>Deliberately <b>not</b> here: {@code find}, which returns an empty result instead of throwing,
 * and {@code isRegistered} / {@code quotedSymbols}, which answer whether an exchange can be priced
 * at all. The first is used only while exhausting routes inside the module; the other two only by
 * the module's own status endpoint. They would be contract for one caller each, and that caller
 * lives in the same artifact.
 *
 * <p>Previously {@code portfolio.domain.QuotationApi} — a port the consumer had to declare and
 * adapt because the provider had no contract of its own. The name also lied: no HTTP was ever
 * involved, and none is now.
 */
public interface QuotationApi extends ModuleApi {

    /**
     * @throws QuoteNotFoundException when no route can price the pair
     * @throws BrokerNotFoundException when this instance serves no such broker
     */
    AssetPriceMetadata fetch(Broker broker, Symbol symbol);

    /** Never fails: an asset nobody described answers {@link AssetBasicInfo#notFound(Ticker)}. */
    AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker);

    void registerBasicInfoAboutAsset(Broker broker, AssetBasicInfo assetBasicInfo);
}
