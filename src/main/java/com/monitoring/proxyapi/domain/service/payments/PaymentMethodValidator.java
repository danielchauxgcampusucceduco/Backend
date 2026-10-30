package com.monitoring.proxyapi.domain.service.payments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stateless checks performed before a payment method is tokenised: Luhn checksum, brand/BIN
 * consistency for cards and Colombian mobile number format for Nequi wallets.
 */
final class PaymentMethodValidator {

    static final Set<String> CARD_METHODS = Set.of("VISA", "MASTERCARD", "AMEX");
    static final Set<String> SUPPORTED_METHODS = Set.of("VISA", "MASTERCARD", "AMEX", "PSE", "NEQUI");

    private PaymentMethodValidator() {
    }

    record Verdict(boolean valid, String maskedReference, List<String> rejectionReasons) {
    }

    static Verdict validateCard(String method, String rawCardNumber) {
        String digits = rawCardNumber.replaceAll("[\\s-]", "");
        List<String> reasons = new ArrayList<>();
        if (!digits.matches("\\d{13,19}")) {
            reasons.add("Card number must contain between 13 and 19 digits");
            return new Verdict(false, "****", reasons);
        }
        if (!passesLuhn(digits)) {
            reasons.add("Card number fails the Luhn checksum");
        }
        Optional<String> detectedBrand = detectBrand(digits);
        if (detectedBrand.isEmpty()) {
            reasons.add("Card BIN does not belong to a supported brand");
        } else if (!detectedBrand.get().equals(method)) {
            reasons.add("Card BIN belongs to " + detectedBrand.get() + " but method " + method + " was declared");
        }
        return new Verdict(reasons.isEmpty(), mask(digits), reasons);
    }

    static Verdict validateWallet(String walletPhone) {
        String digits = walletPhone == null ? "" : walletPhone.replaceAll("\\D", "");
        if (!digits.matches("3\\d{9}")) {
            return new Verdict(false, "***", List.of("Nequi wallets require a 10-digit Colombian mobile number starting with 3"));
        }
        return new Verdict(true, "******" + digits.substring(6), List.of());
    }

    static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    static Optional<String> detectBrand(String digits) {
        if (digits.startsWith("4")) {
            return Optional.of("VISA");
        }
        if (digits.startsWith("34") || digits.startsWith("37")) {
            return Optional.of("AMEX");
        }
        int firstTwo = Integer.parseInt(digits.substring(0, 2));
        int firstFour = Integer.parseInt(digits.substring(0, 4));
        if ((firstTwo >= 51 && firstTwo <= 55) || (firstFour >= 2221 && firstFour <= 2720)) {
            return Optional.of("MASTERCARD");
        }
        return Optional.empty();
    }

    static String mask(String digits) {
        return "**** **** **** " + digits.substring(digits.length() - 4);
    }
}
