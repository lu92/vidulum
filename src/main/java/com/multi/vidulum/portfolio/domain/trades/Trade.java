package com.multi.vidulum.portfolio.domain.trades;

public interface Trade {
    AssetPortion clarifyPurchasedPortion();

    AssetPortion clarifySoldPortion();
}
