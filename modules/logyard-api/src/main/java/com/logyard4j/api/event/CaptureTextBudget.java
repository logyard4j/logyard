package com.logyard4j.api.event;

/** Tracks the independent bounded text allowances used while capturing one event. */
final class CaptureTextBudget {
    private int remainingPayloadCharacters;
    private int remainingExceptionTypeCharacters;
    private int remainingExceptionMessageCharacters;
    private int remainingExceptionFrameCharacters;
    private int remainingIdentityCharacters;
    private int remainingTemplateCharacters;

    CaptureTextBudget(
            int payloadCharacters,
            int exceptionTypeCharacters,
            int exceptionMessageCharacters,
            int exceptionFrameCharacters,
            int identityCharacters,
            int templateCharacters) {
        remainingPayloadCharacters = payloadCharacters;
        remainingExceptionTypeCharacters = exceptionTypeCharacters;
        remainingExceptionMessageCharacters = exceptionMessageCharacters;
        remainingExceptionFrameCharacters = exceptionFrameCharacters;
        remainingIdentityCharacters = identityCharacters;
        remainingTemplateCharacters = templateCharacters;
    }

    CapturedText capturePayload(String source, int fieldLimit) {
        CapturedText captured = capture(source, fieldLimit, remainingPayloadCharacters);
        remainingPayloadCharacters -= length(captured.value());
        return captured;
    }

    CapturedText captureIdentity(String source, int fieldLimit) {
        CapturedText captured = capture(source, fieldLimit, remainingIdentityCharacters);
        remainingIdentityCharacters -= length(captured.value());
        return captured;
    }

    CapturedText captureTemplate(String source, int fieldLimit) {
        CapturedText captured = capture(source, fieldLimit, remainingTemplateCharacters);
        remainingTemplateCharacters -= length(captured.value());
        return captured;
    }

    CapturedText captureExceptionType(String source, int fieldLimit) {
        CapturedText captured = capture(source, fieldLimit, remainingExceptionTypeCharacters);
        remainingExceptionTypeCharacters -= length(captured.value());
        return captured;
    }

    CapturedText captureExceptionMessage(String source, int fieldLimit) {
        CapturedText captured = capture(source, fieldLimit, remainingExceptionMessageCharacters);
        remainingExceptionMessageCharacters -= length(captured.value());
        return captured;
    }

    CapturedText captureExceptionFrame(String source, int fieldLimit) {
        CapturedText captured = capture(source, fieldLimit, remainingExceptionFrameCharacters);
        remainingExceptionFrameCharacters -= length(captured.value());
        return captured;
    }

    int remainingPayloadCharacters() {
        return remainingPayloadCharacters;
    }

    boolean canCaptureExceptionFrame() {
        return remainingExceptionFrameCharacters > 0;
    }

    private static CapturedText capture(String source, int fieldLimit, int remaining) {
        if (source == null) {
            return new CapturedText(null, false);
        }
        String captured = CaptureLimits.truncate(source, Math.min(fieldLimit, remaining));
        return new CapturedText(captured, captured.length() < source.length());
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
