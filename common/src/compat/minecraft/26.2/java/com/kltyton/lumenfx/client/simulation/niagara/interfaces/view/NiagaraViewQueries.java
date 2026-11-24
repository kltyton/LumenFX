package com.kltyton.lumenfx.client.simulation.niagara.interfaces.view;

import com.kltyton.lumenfx.api.client.parameter.NiagaraMovieRenderSample;
import net.minecraft.client.Minecraft;

/** Host values captured on the client thread before CPU work or deferred GPU dispatch. */
public record NiagaraViewQueries(float mouseX, float mouseY, float viewportWidth, float viewportHeight, NiagaraMovieRenderSample movie) {
    public static final NiagaraViewQueries EMPTY = new NiagaraViewQueries(0, 0, 0, 0, NiagaraMovieRenderSample.INACTIVE);

    public static NiagaraViewQueries capture(boolean needsMouse, NiagaraMovieRenderSample movie) {
        if (!needsMouse) return new NiagaraViewQueries(0, 0, 0, 0, movie);
        var client = Minecraft.getInstance();
        var window = client.getWindow();
        int width = window.getWidth(), height = window.getHeight();
        double x = client.mouseHandler.xpos() * width / window.getScreenWidth();
        double y = client.mouseHandler.ypos() * height / window.getScreenHeight();
        return new NiagaraViewQueries((float)x, (float)y, width, height, movie);
    }
}
