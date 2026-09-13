package com.logyard4j.logyard.api.event;

import java.util.function.Supplier;

/** Coordinates one event's graph identity, bounded budgets, and capture-truncation state. */
final class CaptureContext {
    private final CaptureStructuralBudget structure;
    private final CaptureTextBudget text;
    private CaptureReferences references;
    private boolean truncated;

    private CaptureContext(CaptureStructuralBudget structure, CaptureTextBudget text) {
        this.structure = structure;
        this.text = text;
    }

    static CaptureContext create() {
        return new CaptureContext(
                new CaptureStructuralBudget(CaptureLimits.MAX_EVENT_NODES, CaptureLimits.MAX_EVENT_ENTRIES, CaptureLimits.MAX_EVENT_EXCEPTION_NODES, CaptureLimits.MAX_EVENT_STACK_FRAMES),
                new CaptureTextBudget(
                        CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS,
                        CaptureLimits.MAX_EVENT_EXCEPTION_TYPE_CHARS,
                        CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS,
                        CaptureLimits.MAX_EVENT_EXCEPTION_FRAME_CHARS,
                        CaptureLimits.MAX_EVENT_IDENTITY_CHARS,
                        CaptureLimits.MAX_EVENT_TEMPLATE_CHARS));
    }

    static CaptureContext forAttributes(CaptureAllowance allowance) {
        return new CaptureContext(
                new CaptureStructuralBudget(allowance.nodes(), allowance.entries(), 0, 0),
                new CaptureTextBudget(allowance.characters(), 0, 0, 0, 0, 0));
    }

    static CaptureContext currentOrCreate() {
        return CaptureContextScope.currentOrCreate();
    }

    static <T> T within(CaptureContext context, Supplier<T> action) {
        return CaptureContextScope.within(context, action);
    }

    String capturePayloadText(String source, int fieldLimit) {
        return capturedValue(source, text.capturePayload(source, fieldLimit));
    }

    String captureIdentityText(String source, int fieldLimit) {
        return capturedValue(source, text.captureIdentity(source, fieldLimit));
    }

    String captureTemplateText(String source, int fieldLimit) {
        return capturedValue(source, text.captureTemplate(source, fieldLimit));
    }

    CapturedText captureExceptionTypeText(String source, int fieldLimit) {
        return observe(text.captureExceptionType(source, fieldLimit));
    }

    CapturedText captureExceptionMessageText(String source, int fieldLimit) {
        return observe(text.captureExceptionMessage(source, fieldLimit));
    }

    CapturedText captureExceptionFrameText(String source, int fieldLimit) {
        return observe(text.captureExceptionFrame(source, fieldLimit));
    }

    boolean claimNode() {
        return claim(structure.claimNode());
    }

    boolean claimEntry() {
        return claim(structure.claimEntry());
    }

    boolean claimExceptionNode() {
        return claim(structure.claimExceptionNode());
    }

    boolean claimFrame() {
        return claim(structure.claimFrame());
    }

    Object capturedScalar(Object source) {
        return references == null ? null : references.capturedScalar(source);
    }

    void completeScalar(Object source, Object captured) {
        references().completeScalar(source, captured);
    }

    ReferenceState enterValue(Object source) {
        return references().enterValue(source);
    }

    void leaveValue(Object source) {
        references.leaveValue(source);
    }

    ReferenceState enterException(Throwable source) {
        return references().enterException(source);
    }

    void leaveException(Throwable source) {
        references.leaveException(source);
    }

    void markTruncated() {
        truncated = true;
    }

    boolean truncated() {
        return truncated;
    }

    int remainingEntries() {
        return structure.remainingEntries();
    }

    int remainingPayloadCharacters() {
        return text.remainingPayloadCharacters();
    }

    boolean canCaptureExceptionFrame() {
        return text.canCaptureExceptionFrame();
    }

    CaptureAllowance payloadAllowance() {
        return CaptureAllowance.remaining(structure.remainingNodes(), structure.remainingEntries(), text.remainingPayloadCharacters());
    }

    private boolean claim(boolean accepted) {
        if (!accepted) {
            truncated = true;
        }
        return accepted;
    }

    private String capturedValue(String source, String captured) {
        if (source != null && captured.length() < source.length()) {
            truncated = true;
        }
        return captured;
    }

    private CapturedText observe(CapturedText captured) {
        if (captured.truncated()) {
            truncated = true;
        }
        return captured;
    }

    private CaptureReferences references() {
        if (references == null) {
            references = new CaptureReferences();
        }
        return references;
    }

    enum ReferenceState {
        FRESH,
        CYCLE,
        SHARED
    }
}
