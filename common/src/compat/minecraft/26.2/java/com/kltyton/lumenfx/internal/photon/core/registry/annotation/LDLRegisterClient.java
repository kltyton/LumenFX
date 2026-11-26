package com.kltyton.lumenfx.internal.photon.core.registry.annotation;

import com.kltyton.lumenfx.internal.photon.core.registry.RegistrationEnvironment;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface LDLRegisterClient {
    String registry();
    String name();
    String group() default "";
    String modID() default "";
    int priority() default 0;
    RegistrationEnvironment environment() default RegistrationEnvironment.BOTH;
}
