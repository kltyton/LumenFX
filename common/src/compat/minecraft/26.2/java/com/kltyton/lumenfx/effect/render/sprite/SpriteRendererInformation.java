package com.kltyton.lumenfx.effect.render.sprite;

/** Immutable sprite-renderer query values, including defaults when its renderer pointer is null. */
public record SpriteRendererInformation(boolean valid, int sourceMode, int alignment, int facingMode,
                                        boolean subImageBlend, float columns, float rows) { }
