package dev.subscriptionmeter;

import dev.subscriptionmeter.limits.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class RequestBodyBudgetTest {
    @Test void chunkedUploadsReserveWorstCaseAndReleaseOnce(){
        var budget=new RequestBodyBudget();
        var a=budget.reserve(-1);var b=budget.reserve(-1);var c=budget.reserve(-1);var d=budget.reserve(-1);
        assertThatThrownBy(()->budget.reserve(-1)).isInstanceOf(ResponseStatusException.class);
        a.close();a.close();var replacement=budget.reserve(-1);
        assertThatThrownBy(()->budget.reserve(-1)).isInstanceOf(ResponseStatusException.class);
        replacement.close();b.close();c.close();d.close();
        assertThatThrownBy(()->budget.reserve(RequestBodyBudget.MAX_BODY+1L)).isInstanceOf(ResponseStatusException.class);
    }
}
