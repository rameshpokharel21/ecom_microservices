package com.ramesh.order.exceptions;

//Raised by a circuit breaker fallback when a downstream service could not be reached:
//the breaker is OPEN, the call timed out, or the transport failed. Distinct from
//ProductNotFoundException / UserNotFoundException, which mean the service answered
//correctly and the thing genuinely does not exist.
public class ServiceUnavailableException extends RuntimeException {

    private final String serviceName;

    public ServiceUnavailableException(String serviceName, Throwable cause) {
        super(serviceName + " is currently unavailable", cause);
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
