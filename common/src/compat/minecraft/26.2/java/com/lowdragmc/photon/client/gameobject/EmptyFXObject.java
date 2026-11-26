/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject;

import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import lombok.Getter;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@Getter
public class EmptyFXObject extends FXObject {

    @LDLRegisterClient(name = "empty", registry = "photon:fx_object", priority = -99)
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new EmptyFXObject();
        }
    };

    public EmptyFXObject() {
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
    }

}
