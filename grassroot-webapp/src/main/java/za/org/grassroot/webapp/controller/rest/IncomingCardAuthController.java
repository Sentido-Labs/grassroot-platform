package za.org.grassroot.webapp.controller.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.org.grassroot.integration.payments.PaymentResponse;
import za.org.grassroot.integration.payments.PaymentResultType;
import za.org.grassroot.integration.payments.PaymentServiceBroker;

/**
 * Created by luke on 2016/11/14.
 */
@Controller
@RequestMapping("/cardauth")
public class IncomingCardAuthController {

    private static final Logger logger = LoggerFactory.getLogger(IncomingCardAuthController.class);

    private PaymentServiceBroker paymentBroker;

    @Autowired
    public IncomingCardAuthController(PaymentServiceBroker paymentBroker) {
        this.paymentBroker = paymentBroker;
    }

    @RequestMapping("/3dsecure/response/{type}")
    public String receiveAuthorizationResponse(@PathVariable String type, @RequestParam String id,
                                               @RequestParam String resourcePath, RedirectAttributes attributes) {

        logger.info("paymentId: {}, resourcePath: {}", id, resourcePath);

        // todo : move this check deeper into the redirect chain
        PaymentResponse response = paymentBroker.asyncPaymentCheckResult(id, resourcePath);
        attributes.addAttribute("paymentId", id);
        attributes.addAttribute("paymentRef", response.getReference());
        attributes.addAttribute("succeeded", response.getType().equals(PaymentResultType.SUCCESS));
        return "redirect:/account/payment/done/redirect";
    }

}