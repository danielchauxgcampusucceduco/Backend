package com.monitoring.proxyapi.domain.exception;

public class InvalidRequestParameterException extends DomainException {

    private final String parameter;

    public InvalidRequestParameterException(String parameter, String reason) {
        super("INVALID_PARAMETER", ErrorCategory.INVALID_REQUEST, "Parameter '" + parameter + "' " + reason);
        this.parameter = parameter;
    }

    public static InvalidRequestParameterException missing(String parameter) {
        return new InvalidRequestParameterException(parameter, "is required");
    }

    public String getParameter() {
        return parameter;
    }
}
