/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.integration;

import com.kltyton.lumenfx.internal.photon.core.syncdata.AccessorRegistries;
import com.kltyton.lumenfx.internal.photon.core.syncdata.accessor.direct.CustomDirectAccessor;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;

import java.util.concurrent.atomic.AtomicBoolean;

/** Registers payload codecs used by Photon .fx deserialization without requiring external LDLib2. */
public final class PhotonLDLibPlugin {
    private static final int PAYLOAD_PRIORITY = 1000;
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    public static void ensureLoaded() {
        if (LOADED.compareAndSet(false, true)) {
            registerAll();
        }
    }

    public void onLoad() {
        ensureLoaded();
    }

    private static void registerAll() {
        register(NumberFunction.class, NumberFunction.CODEC);
        register(NumberFunction3.class, NumberFunction3.CODEC);
        register(ECBCurves.class, ECBCurves.CODEC);
        register(IShape.class, IShape.CODEC);
        register(IModelSource.class, IModelSource.CODEC);
        AccessorRegistries.registerAccessor(CustomDirectAccessor.builder(IMaterial.class)
                .codec(IMaterial.CODEC)
                .streamCodec(ByteBufCodecs.fromCodec(IMaterial.CODEC))
                .copyMark(IMaterial::copy)
                .build(), PAYLOAD_PRIORITY);
    }

    private static <T> void register(Class<T> type, Codec<T> codec) {
        AccessorRegistries.registerAccessor(CustomDirectAccessor.builder(type)
                .codec(codec)
                .streamCodec(ByteBufCodecs.fromCodec(codec))
                .codecMark()
                .build(), PAYLOAD_PRIORITY);
    }
}
