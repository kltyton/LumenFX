package com.kltyton.lumenfx.tools.shader;

public final class BakeException extends RuntimeException {
    public BakeException(final String message) {
        super(message);
    }

    public BakeException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
