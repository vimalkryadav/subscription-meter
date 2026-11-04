package dev.subscriptionmeter.accounting;

import java.math.BigDecimal;
import java.util.*;

/** API-equivalent token value only; never converts dollars into subscription allowance. */
public final class PriceCalculator {
    private static final BigDecimal MILLION=BigDecimal.valueOf(1_000_000);
    public record Estimate(String status,BigDecimal knownSubtotalUsd,List<String> missingComponents,String priceVersion) {}
    public Estimate estimate(Map<String,Long> components,boolean complete,Map<String,BigDecimal> rates,String version) {
        if(version==null || rates==null) return new Estimate("unpriced",null,List.of("price_version"),null);
        var quantities=new LinkedHashMap<>(components);
        var missing=new LinkedHashSet<String>();
        for(String key:List.of("input","output","cache_read","cache_write")) if(!quantities.containsKey(key)) missing.add(key);
        Long writes=quantities.remove("cache_write");
        if(writes!=null&&writes<0)missing.add("invalid_cache_write");
        if(writes!=null && writes>0) {
            Long five=quantities.get("cache_write_5m"),hour=quantities.get("cache_write_1h");
            if(five==null || hour==null) {missing.add("cache_write_ttl"); quantities.remove("cache_write_5m");quantities.remove("cache_write_1h");}
            else if(five<0 || hour<0 || five>writes || hour!=writes-five) {
                missing.add("invalid_cache_write_breakdown");quantities.remove("cache_write_5m");quantities.remove("cache_write_1h");
            }
        } else if(writes!=null && writes==0) {
            if(quantities.getOrDefault("cache_write_5m",0L)>0 || quantities.getOrDefault("cache_write_1h",0L)>0) missing.add("invalid_cache_write_breakdown");
            quantities.remove("cache_write_5m");quantities.remove("cache_write_1h");
        } else {quantities.remove("cache_write_5m");quantities.remove("cache_write_1h");}
        BigDecimal subtotal=BigDecimal.ZERO;
        int priced=0;
        for(var item:quantities.entrySet()) {
            if(item.getValue()<0) {missing.add("invalid_"+item.getKey());continue;}
            BigDecimal rate=rates.get(item.getKey());
            if(rate==null) {if(item.getValue()>0)missing.add("rate_"+item.getKey());continue;}
            if(rate.signum()<0||rate.precision()>20||Math.abs((long)rate.scale())>12){missing.add("invalid_rate_"+item.getKey());continue;}
            BigDecimal amount=rate.multiply(BigDecimal.valueOf(item.getValue()));
            subtotal=subtotal.add(item.getKey().startsWith("web_")?amount:amount.divide(MILLION)); priced++;
        }
        if(!complete) missing.add("final_usage");
        if(priced==0) return new Estimate("unpriced",null,List.copyOf(missing),version);
        return new Estimate(missing.isEmpty()?"complete":"partial",subtotal,List.copyOf(missing),version);
    }
}
