package com.tcs.invoicefactoring.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Transaction management configuration.
 *
 * Ordered precedence:
 * - TransactionInterceptor executes at order 100 (starts the database transaction).
 * - TenantRlsAspect executes at order 200 (runs INSIDE the active transaction to set PostgreSQL RLS context).
 */
@Configuration
@EnableTransactionManagement(order = 100)
public class TransactionConfig {
}
