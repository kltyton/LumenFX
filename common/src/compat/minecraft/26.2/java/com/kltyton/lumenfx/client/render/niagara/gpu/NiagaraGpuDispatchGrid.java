package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

/** Device-specific direct dispatch extents following Niagara's workgroup splitting rules. */
public final class NiagaraGpuDispatchGrid {
    public record Extent(int x, int y, int z) { }
    public record Plan(Extent extent, int logicalCount) { }
    private final int[] maximumGroups = new int[3];

    public NiagaraGpuDispatchGrid(ComputeRuntime runtime) {
        RenderSystem.assertOnRenderThread();
        for (int i = 0; i < maximumGroups.length; i++) {
            maximumGroups[i] = runtime.maxWorkGroups(i);
            if (maximumGroups[i] <= 0) throw new IllegalStateException("Invalid GPU dispatch limit on axis " + i);
        }
    }

    public @Nullable Plan direct(NiagaraGpuStageSchedule.Invocation stage, ShaderProgram shader,
                                   int particleCount, @Nullable Extent interfaceElements) {
        if (stage.indirect()) throw new IllegalArgumentException("Indirect Niagara stage requires GPU dispatch arguments");
        int x, y, z;
        String dispatch = stage.dispatchType();
        switch (stage.iterationSource()) {
            case "Particles" -> { x = particleCount; y = 1; z = 1; dispatch = "OneD"; }
            case "DataInterface" -> {
                if (interfaceElements == null) throw new IllegalArgumentException("Missing Niagara interface iteration dimensions");
                x = interfaceElements.x(); y = interfaceElements.y(); z = interfaceElements.z();
                if (x < 0 || y < 0 || z < 0
                        || z != 0 && (long)x * y >= Math.ceilDiv((long)Integer.MAX_VALUE, z))
                    throw new IllegalArgumentException("Invalid Niagara interface iteration dimensions");
                if (dispatch.equals("OneD")) { x = Math.multiplyExact(Math.multiplyExact(x, y), z); y = 1; z = 1; }
            }
            case "DirectSet" -> {
                x = stage.elementsX(); y = stage.elementsY(); z = stage.elementsZ();
                if (dispatch.equals("Custom")) throw new IllegalArgumentException("Custom direct Niagara dispatch is not supported by UE");
                switch (stage.directElementType()) {
                    case "NumThreads", "NumThreadsNoClipping" -> { }
                    case "NumGroups" -> {
                        x = Math.multiplyExact(x, shader.workgroupX());
                        y = Math.multiplyExact(y, shader.workgroupY());
                        z = Math.multiplyExact(z, shader.workgroupZ());
                    }
                    default -> throw new IllegalArgumentException("Unknown Niagara direct dispatch element type");
                }
            }
            default -> throw new IllegalArgumentException("Unknown Niagara GPU iteration source");
        }
        if (x <= 0 || y <= 0 || z <= 0) return null;
        int logicalCount = Math.multiplyExact(Math.multiplyExact(x, y), z);
        if (dispatch.equals("OneD")) {
            int groups = Math.ceilDiv(x, shader.workgroupX());
            if (groups > maximumGroups[0]) {
                y = Math.ceilDiv(groups, maximumGroups[0]);
                x = Math.multiplyExact(Math.ceilDiv(groups, y), shader.workgroupX());
            }
        }
        int[] extents = {x, y, z};
        int[] groupSize = {shader.workgroupX(), shader.workgroupY(), shader.workgroupZ()};
        for (int axis = 0; axis < 3; axis++)
            if (Math.ceilDiv(extents[axis], groupSize[axis]) > maximumGroups[axis])
                throw new IllegalArgumentException("Niagara dispatch exceeds GPU workgroup limit on axis " + axis);
        return new Plan(new Extent(x, y, z), logicalCount);
    }
}
