package com.kltyton.lumenfx.effect.render;

/** Source compositing order when the host has no intervening DOF or motion-blur pass. */
public enum TranslucencyPass {
    BEFORE_DOF,
    AFTER_DOF,
    AFTER_MOTION_BLUR
}
