package com.kltyton.lumenfx.client.render.texture.virtual;

import com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionLogLayout;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Blaze3D's graphics bind groups carry the feedback/log buffer handles, with actual storage descriptors on Vulkan. */
public final class VirtualTextureFeedbackLayout {
    public static final String NAME = "LfxVirtualTextureFeedback";
    public static final int GL_SLOT = 0;
    public static final List<String> GRAPHICS_STORAGE_BUFFERS = List.of(NAME, TranslucentMotionLogLayout.NAME);
    private VirtualTextureFeedbackLayout() { }

    public static void add(BindGroupLayout.Builder layout) { layout.withUniform(NAME, UniformType.UNIFORM_BUFFER); }

    public static boolean isGraphicsStorageBuffer(String name) {
        return NAME.equals(name) || TranslucentMotionLogLayout.NAME.equals(name);
    }

    public static int bindingOffset(ByteBuffer spirv) {
        return bindingOffset(spirv, NAME);
    }

    public static int bindingOffset(ByteBuffer spirv, String bufferName) {
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.callocPointer(1);
            check(Spvc.spvc_context_create(pointer));
            long context = pointer.get(0);
            try {
                var words = spirv.asIntBuffer();
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, pointer.get(0),
                        Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                check(Spvc.spvc_compiler_create_shader_resources(compiler, pointer));
                var count = stack.callocPointer(1);
                check(Spvc.spvc_resources_get_resource_list_for_type(pointer.get(0), Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, pointer, count));
                if (count.get(0) == 0) return -1;
                var resources = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
                for (int index = 0; index < resources.capacity(); index++) {
                    var resource = resources.get(index);
                    if (!bufferName.equals(resource.nameString())) continue;
                    var offset = stack.mallocInt(1);
                    if (!Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource.id(), 33, offset))
                        throw new IllegalArgumentException("Graphics storage buffer " + bufferName + " has no SPIR-V binding");
                    return offset.get(0);
                }
                return -1;
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void check(int result) {
        if (result != Spvc.SPVC_SUCCESS) throw new IllegalArgumentException("Unable to reflect graphics virtual texture feedback: SPIRV-Cross " + result);
    }
}
