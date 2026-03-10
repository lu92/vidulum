package com.multi.vidulum.recurring_rules.domain.exceptions;

/**
 * Thrown when dashboard query parameters are invalid.
 */
public class InvalidDashboardParameterException extends RuntimeException {

    private final String parameterName;
    private final Object providedValue;
    private final int minValue;
    private final int maxValue;

    public InvalidDashboardParameterException(String parameterName, Object providedValue, int minValue, int maxValue) {
        super(String.format("%s must be between %d and %d, but was: %s", parameterName, minValue, maxValue, providedValue));
        this.parameterName = parameterName;
        this.providedValue = providedValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public String getParameterName() {
        return parameterName;
    }

    public Object getProvidedValue() {
        return providedValue;
    }

    public int getMinValue() {
        return minValue;
    }

    public int getMaxValue() {
        return maxValue;
    }
}
