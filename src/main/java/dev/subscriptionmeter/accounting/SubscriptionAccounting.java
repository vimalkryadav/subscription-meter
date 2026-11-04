package dev.subscriptionmeter.accounting;

import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Declared allocation policy, separate from provider quota and actual metered charges. */
@Service
public class SubscriptionAccounting {
    public record Invoice(String provider,String upstreamAccount,Instant periodStart,Instant periodEnd,
            BigDecimal amount,String currency,BigDecimal gatewayFraction,String allocationBasis,String provenance) {}
    private final JdbcTemplate jdbc;
    private final AccountingService accounting;
    private final TransactionTemplate tx;
    public SubscriptionAccounting(JdbcTemplate jdbc,AccountingService accounting){this.jdbc=jdbc;this.accounting=accounting;
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));}
    public String add(Invoice i,String actor) {
        if(i==null || !Set.of("anthropic","openai").contains(Objects.toString(i.provider(),"")) || i.upstreamAccount()==null
            || !i.upstreamAccount().matches("[a-zA-Z0-9_-]{1,80}") || i.periodStart()==null || i.periodEnd()==null
            || !i.periodEnd().isAfter(i.periodStart()) || i.periodEnd().isAfter(i.periodStart().plusSeconds(366L*86400))
            || i.amount()==null || i.amount().signum()<0 || i.amount().compareTo(new BigDecimal("1000000"))>0 || i.amount().stripTrailingZeros().scale()>2
            || i.gatewayFraction()==null || i.gatewayFraction().signum()<0 || i.gatewayFraction().compareTo(BigDecimal.ONE)>0 || i.gatewayFraction().scale()>6
            || !Set.of("USD","INR","EUR","GBP").contains(Objects.toString(i.currency(),""))
            || !Set.of("reported_tokens","equal").contains(Objects.toString(i.allocationBasis(),""))
            || !Set.of("manual_invoice","estimated_subscription").contains(Objects.toString(i.provenance(),"")))
            throw new IllegalArgumentException("Invalid subscription expense");
        String id=UUID.randomUUID().toString();
        tx.executeWithoutResult(s->{
            jdbc.update("""
                INSERT INTO subscription_invoices(id,provider,upstream_account,period_start,period_end,amount,currency,gateway_fraction,allocation_basis,provenance)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,i.provider(),i.upstreamAccount(),Timestamp.from(i.periodStart()),Timestamp.from(i.periodEnd()),i.amount(),i.currency(),i.gatewayFraction(),i.allocationBasis(),i.provenance());
            accounting.log(actor,"subscription_expense.create",id,Map.of("currency",i.currency(),"amount",i.amount(),"allocationBasis",i.allocationBasis(),"gatewayFraction",i.gatewayFraction()));
        });return id;
    }
    public List<Map<String,Object>> list(){return jdbc.queryForList("SELECT * FROM subscription_invoices ORDER BY period_start DESC,id DESC LIMIT 100");}
    public Map<String,Object> allocation(String id) {
        var rows=jdbc.queryForList("SELECT * FROM subscription_invoices WHERE id=?",id);
        if(rows.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        var invoice=rows.getFirst();
        var members=jdbc.queryForList("""
            SELECT r.member_id AS "memberId",m.name,COUNT(*) AS requests,
            SUM(r.input_tokens+r.output_tokens+r.cache_creation_input_tokens+r.cache_read_input_tokens) AS tokens,
            SUM(CASE WHEN r.usage_complete THEN 1 ELSE 0 END) AS "completeUsageRequests"
            FROM requests r JOIN members m ON m.id=r.member_id
            WHERE r.provider=? AND r.upstream_account=? AND r.started_at>=? AND r.started_at<?
            GROUP BY r.member_id,m.name ORDER BY r.member_id
            """,invoice.get("provider"),invoice.get("upstream_account"),invoice.get("period_start"),invoice.get("period_end"));
        boolean equal="equal".equals(invoice.get("allocation_basis"));
        BigDecimal totalWeight=BigDecimal.ZERO;
        for(var member:members){BigDecimal weight=equal?BigDecimal.ONE:BigDecimal.valueOf(AccountingService.number(member.get("tokens")));member.put("weight",weight);totalWeight=totalWeight.add(weight);}
        BigDecimal amount=(BigDecimal)invoice.get("amount"),fraction=(BigDecimal)invoice.get("gateway_fraction");
        BigDecimal pool=totalWeight.signum()==0?BigDecimal.ZERO:amount.multiply(fraction).setScale(2,RoundingMode.HALF_UP);
        BigDecimal allocated=BigDecimal.ZERO;
        var remainders=new ArrayList<Map.Entry<Map<String,Object>,BigDecimal>>();
        for(var member:members){
            BigDecimal weight=(BigDecimal)member.get("weight");
            BigDecimal raw=totalWeight.signum()==0?BigDecimal.ZERO:pool.multiply(weight).divide(totalWeight,24,RoundingMode.DOWN);
            BigDecimal value=raw.setScale(2,RoundingMode.DOWN);
            member.put("allocatedAmount",value);allocated=allocated.add(value);
            if(weight.signum()>0)remainders.add(Map.entry(member,raw.subtract(value)));
        }
        remainders.sort(Comparator.<Map.Entry<Map<String,Object>,BigDecimal>,BigDecimal>comparing(Map.Entry::getValue).reversed()
            .thenComparing(entry->entry.getKey().get("memberId").toString()));
        int pennies=pool.subtract(allocated).movePointRight(2).intValueExact();
        for(int n=0;n<pennies;n++){
            var member=remainders.get(n).getKey();member.put("allocatedAmount",((BigDecimal)member.get("allocatedAmount")).add(new BigDecimal("0.01")));
        }
        allocated=pool;
        boolean complete=!members.isEmpty()&&members.stream().allMatch(m->AccountingService.number(m.get("requests"))==AccountingService.number(m.get("completeUsageRequests")));
        return Map.of("invoice",invoice,"members",members,"allocatedAmount",allocated,"unallocatedAmount",amount.subtract(allocated),
            "currency",invoice.get("currency"),"usageCoverage",members.isEmpty()?"unavailable":complete?"complete":"partial","calculatedAt",Instant.now(),
            "note","Declared expense allocation, not incremental charges or measured subscription quota consumption. Equal split includes members with recorded requests in this period.");
    }
}
