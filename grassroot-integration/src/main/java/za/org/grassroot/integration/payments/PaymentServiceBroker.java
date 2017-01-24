package za.org.grassroot.integration.payments;

import za.org.grassroot.core.domain.AccountBillingRecord;

/**
 * Created by luke on 2016/10/26.
 */
public interface PaymentServiceBroker {

    PaymentResponse asyncPaymentInitiate(String accountUid, PaymentMethod method, AccountBillingRecord amountToPay, String returnToUrl);

    PaymentResponse asyncPaymentCheckResult(String paymentId, String resourcePath);

    PaymentResponse initiateMobilePayment(AccountBillingRecord record);

    PaymentResponse checkMobilePaymentResult(String paymentId);

    void processAccountPaymentsOutstanding();
}