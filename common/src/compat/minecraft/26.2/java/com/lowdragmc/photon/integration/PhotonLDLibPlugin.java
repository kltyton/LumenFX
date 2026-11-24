/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.integration;

import com.lowdragmc.lowdraglib2.plugin.ILDLibPlugin;
import com.lowdragmc.lowdraglib2.plugin.LDLibPlugin;
import com.lowdragmc.lowdraglib2.syncdata.AccessorRegistries;
import com.lowdragmc.lowdraglib2.syncdata.accessor.direct.CustomDirectAccessor;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;

/** Installs the payload codecs used by Photon's .fx objects during LDLib startup. */
@LDLibPlugin
public final class PhotonLDLibPlugin implements ILDLibPlugin {
    private static final int PAYLOAD_PRIORITY = 1000;

    @Override
    public void onLoad() {
        register(NumberFunction.class, NumberFunction.CODEC);
        register(NumberFunction3.class, NumberFunction3.CODEC);
        // This codec also reads the legacy eight-float curve points.
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
