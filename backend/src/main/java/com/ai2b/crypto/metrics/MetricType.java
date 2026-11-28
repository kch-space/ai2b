package com.ai2b.crypto.metrics;

public enum MetricType {
    OPEN_INTEREST("/futures/data/openInterestHist", false, true),
    FUNDING_SNAPSHOT("/fapi/v1/premiumIndex", false, false),
    FUNDING_HISTORY("/fapi/v1/fundingRate", false, false),
    GLOBAL_ACCOUNT_RATIO("/futures/data/globalLongShortAccountRatio", false, true),
    TOP_ACCOUNT_RATIO("/futures/data/topLongShortAccountRatio", true, true),
    TOP_POSITION_RATIO("/futures/data/topLongShortPositionRatio", true, true);

    public final String path;
    public final boolean requiresKey;
    public final boolean fiveMinute;
    MetricType(String path, boolean requiresKey, boolean fiveMinute) {
        this.path = path; this.requiresKey = requiresKey; this.fiveMinute = fiveMinute;
    }
}
