package com.logyard4j.logyard.api.event;

/** Captures one detached stack frame and reports field-level truncation. */
final class ExceptionFrameCapture {
    private ExceptionFrameCapture() {
    }

    static Result capture(StackTraceElement frame, CaptureContext context) {
        CapturedText classLoader = context.captureExceptionFrameText(frame.getClassLoaderName(), CaptureLimits.MAX_NAME_CHARS);
        CapturedText module = context.captureExceptionFrameText(frame.getModuleName(), CaptureLimits.MAX_NAME_CHARS);
        CapturedText moduleVersion = context.captureExceptionFrameText(frame.getModuleVersion(), CaptureLimits.MAX_NAME_CHARS);
        CapturedText className = context.captureExceptionFrameText(frame.getClassName(), CaptureLimits.MAX_NAME_CHARS);
        CapturedText methodName = context.captureExceptionFrameText(frame.getMethodName(), CaptureLimits.MAX_NAME_CHARS);
        CapturedText fileName = context.captureExceptionFrameText(frame.getFileName(), CaptureLimits.MAX_NAME_CHARS);
        StackTraceElement captured = new StackTraceElement(
                classLoader.value(),
                module.value(),
                moduleVersion.value(),
                className.value(),
                methodName.value(),
                fileName.value(),
                frame.getLineNumber());
        return new Result(
                captured,
                classLoader.truncated()
                        || module.truncated()
                        || moduleVersion.truncated()
                        || className.truncated()
                        || methodName.truncated()
                        || fileName.truncated());
    }

    record Result(StackTraceElement frame, boolean truncated) {
    }
}
