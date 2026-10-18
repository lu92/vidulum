package com.multi.vidulum;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.forecast.CashFlowForecastApi;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionApi;
import com.multi.vidulum.portfolio.api.PortfolioModuleApi;
import com.multi.vidulum.quotation.api.QuotationApi;
import com.multi.vidulum.trading.api.TradingModuleApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every implementation module reaches the running application.
 *
 * <p>The runtime counterpart to {@code ModuleBoundariesArchTest}: that one checks nobody reaches
 * into a module they should not, this one checks the modules are there at all. They answer
 * different failure modes, and this one is the quieter of the two — a module missing from the
 * bootstrap's classpath does not fail any build. Its beans simply are not created, every other
 * test still passes, and the feature is gone.
 *
 * <p>That is not hypothetical: until this test, {@code vidulum-recurring-rules} arrived
 * transitively through {@code vidulum-cashflow}, which declared it at compile scope while using
 * nothing from it. Dropping that declaration to test scope — the correct thing to do — would have
 * removed the module from the application without a single red test, because the only test of
 * recurring rules lives in cashflow and needs no bootstrap at all.
 *
 * <p>Asserted by bean type, not by class name: a contract present means the module behind it was
 * found, scanned and wired.
 */
@ExtendWith(SpringExtension.class)
class BootstrapWiresEveryModuleTest extends AppIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theContractOfEveryWiredModuleIsAnswered() {
        assertThat(context.getBeansOfType(PortfolioModuleApi.class)).hasSize(1);
        assertThat(context.getBeansOfType(TradingModuleApi.class)).hasSize(1);
        assertThat(context.getBeansOfType(QuotationApi.class)).hasSize(1);
        assertThat(context.getBeansOfType(ExchangeConnectionApi.class)).hasSize(1);
        assertThat(context.getBeansOfType(CashFlowModuleApi.class))
                .as("one implementation, so that the HTTP adapter cannot run alongside the local one")
                .hasSize(1);
        assertThat(context.getBeansOfType(CashFlowModuleApi.class).values())
                .as("and with the transport property unset it is the in-process one - a monolith "
                        + "must not reach its own aggregate over loopback HTTP")
                .singleElement()
                .isNot(new org.assertj.core.api.Condition<>(
                        api -> api.getClass().getSimpleName().startsWith("Http"),
                        "an HTTP adapter"));
        assertThat(context.getBeansOfType(CashFlowForecastApi.class))
                .as("the forecast answers for itself. Its one consumer used to ask for month "
                        + "statuses through a port whose only implementation was HTTP, so staging "
                        + "a statement made this application call its own endpoint over loopback")
                .hasSize(1);
    }

    /**
     * These four have no module contract to ask for, so the endpoint is the next best witness: a
     * controller bean exists only if its module was on the classpath and scanned.
     */
    @Test
    void theModulesWithoutAContractAreWiredToo() {
        assertThat(beanNamesEndingWith("RecurringRulesController"))
                .as("recurring rules - declared by the bootstrap since step 13, not inherited from cashflow")
                .hasSize(1);
        assertThat(beanNamesEndingWith("PnlRestController")).as("pnl").hasSize(1);
        assertThat(beanNamesEndingWith("RiskManagementRestController")).as("risk").hasSize(1);
        assertThat(beanNamesEndingWith("UserFinancialProfileRestController")).as("user financial profile").hasSize(1);
    }

    private java.util.List<String> beanNamesEndingWith(String simpleName) {
        return java.util.Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> name.equalsIgnoreCase(simpleName)
                        || name.endsWith("." + simpleName)
                        || name.equalsIgnoreCase(Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1)))
                .toList();
    }
}
