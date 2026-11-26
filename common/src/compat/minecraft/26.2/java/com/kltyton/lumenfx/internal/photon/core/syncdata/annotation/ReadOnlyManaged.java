package com.kltyton.lumenfx.internal.photon.core.syncdata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ReadOnlyManaged {
    String onDirtyMethod() default "";
    String serializeMethod();
    String deserializeMethod();
}
