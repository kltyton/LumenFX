package com.kltyton.lumenfx.tools.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcBufferRange;
import org.lwjgl.util.spvc.SpvcCombinedImageSampler;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.util.spvc.Spv;

final class SpirvReflection {
    record Resource(
            String name,
            int id,
            int typeId,
            int baseTypeId,
            int descriptorSet,
            int descriptorBinding,
            int baseType,
            int[] arrayDimensions,
            int imageDimension,
            boolean imageArrayed,
            boolean imageDepth,
            int imageStorageFormat,
            int imageAccess,
            boolean nonWritable,
            boolean nonReadable,
            int imageSampledBaseType
    ) {
    }

    record CombinedSampler(int combinedId, int imageId, int samplerId, String combinedName, String imageName, String samplerName) {
    }

    record Member(
            String name,
            String type,
            String baseType,
            int bitWidth,
            int vectorSize,
            int columns,
            int[] arrayDimensions,
            int offset,
            int size,
            int arrayStride,
            int matrixStride,
            String matrixOrder,
            boolean rowMajor,
            List<Member> members
    ) {
    }

    record UniformBuffer(String name, int id, int descriptorSet, int descriptorBinding, int size, List<Member> members) {
    }

    record StageIo(
            String name,
            int location,
            String type,
            String baseType,
            int bitWidth,
            int vectorSize,
            int columns,
            int[] arrayDimensions
    ) {
    }

    record Reflection(
            int[] localSize,
            Set<Integer> capabilities,
            List<Resource> uniformBuffers,
            List<Resource> storageBuffers,
            List<Resource> sampledImages,
            List<Resource> separateImages,
            List<Resource> separateSamplers,
            List<Resource> storageImages,
            List<CombinedSampler> combinedSamplers,
            List<UniformBuffer> uniformLayouts,
            List<UniformBuffer> storageBufferLayouts,
            List<StageIo> stageInputs,
            List<StageIo> stageOutputs
    ) {
        boolean hasStorageBuffers() {
            return !this.storageBuffers.isEmpty();
        }

        boolean hasWaveOperations() {
            for (int capability : this.capabilities) {
                if (capability == Spv.SpvCapabilityGroupNonUniform
                        || capability == Spv.SpvCapabilityGroupNonUniformVote
                        || capability == Spv.SpvCapabilityGroupNonUniformArithmetic
                        || capability == Spv.SpvCapabilityGroupNonUniformBallot
                        || capability == Spv.SpvCapabilityGroupNonUniformShuffle
                        || capability == Spv.SpvCapabilityGroupNonUniformShuffleRelative
                        || capability == Spv.SpvCapabilityGroupNonUniformClustered
                        || capability == Spv.SpvCapabilityGroupNonUniformQuad) {
                    return true;
                }
            }
            return false;
        }

        boolean hasStorageImageExtendedFormats() {
            return this.capabilities.contains(Spv.SpvCapabilityStorageImageExtendedFormats);
        }

        boolean hasStorageImageWriteWithoutFormat() {
            return this.capabilities.contains(Spv.SpvCapabilityStorageImageWriteWithoutFormat);
        }
        boolean hasStorageImageReadWithoutFormat() {
            return this.capabilities.contains(Spv.SpvCapabilityStorageImageReadWithoutFormat);
        }

        boolean hasNativeFp16() {
            return this.capabilities.contains(Spv.SpvCapabilityFloat16)
                    || this.capabilities.contains(Spv.SpvCapabilityInt16)
                    || this.capabilities.contains(Spv.SpvCapabilityFloat16Buffer);
        }

        boolean hasStorage16() {
            return this.capabilities.contains(Spv.SpvCapabilityStorageBuffer16BitAccess)
                    || this.capabilities.contains(Spv.SpvCapabilityStorageUniform16)
                    || this.capabilities.contains(Spv.SpvCapabilityStoragePushConstant16)
                    || this.capabilities.contains(Spv.SpvCapabilityStorageInputOutput16);
        }

        boolean hasSubgroupExtended() {
            return this.capabilities.contains(Spv.SpvCapabilityGroupNonUniformClustered)
                    || this.capabilities.contains(Spv.SpvCapabilityGroupNonUniformQuad)
                    || this.capabilities.contains(Spv.SpvCapabilityGroupNonUniformShuffleRelative);
        }
    }

    private SpirvReflection() {
    }

    static Reflection reflect(final byte[] spirv) {
        DxcCompiler.validateSpirv(spirv, "SPIR-V reflection input");
        final IntBuffer words = MemoryUtil.memAllocInt(spirv.length / 4);
        try {
            final ByteBuffer bytes = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
            while (bytes.remaining() >= 4) {
                words.put(bytes.getInt());
            }
            words.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final org.lwjgl.PointerBuffer contextOut = stack.mallocPointer(1);
                check(Spvc.spvc_context_create(contextOut), "spvc_context_create");
                final long context = contextOut.get(0);
                try {
                    final org.lwjgl.PointerBuffer parsedOut = stack.mallocPointer(1);
                    check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), parsedOut),
                            "spvc_context_parse_spirv");
                    final org.lwjgl.PointerBuffer compilerOut = stack.mallocPointer(1);
                    check(Spvc.spvc_context_create_compiler(
                                    context,
                                    Spvc.SPVC_BACKEND_GLSL,
                                    parsedOut.get(0),
                                    Spvc.SPVC_CAPTURE_MODE_COPY,
                                    compilerOut
                            ),
                            "spvc_context_create_compiler");
                    final long compiler = compilerOut.get(0);
                    return reflectCompiler(compiler, spirv);
                } finally {
                    Spvc.spvc_context_destroy(context);
                }
            }
        } finally {
            MemoryUtil.memFree(words);
        }
    }

    private static Reflection reflectCompiler(final long compiler, final byte[] spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final org.lwjgl.PointerBuffer resourcesOut = stack.mallocPointer(1);
            check(Spvc.spvc_compiler_create_shader_resources(compiler, resourcesOut),
                    "spvc_compiler_create_shader_resources");
            final long resources = resourcesOut.get(0);

            final List<Resource> uniformBuffers = resources(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stack);
            final List<Resource> storageBuffers = resources(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, stack);
            final List<Resource> sampledImages = resources(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stack);
            final List<Resource> separateImages = resources(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, stack);
            final List<Resource> separateSamplers = resources(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, stack);
            final List<Resource> storageImages = resources(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, stack);

            final List<UniformBuffer> layouts = new ArrayList<>();
            for (Resource resource : uniformBuffers) {
                layouts.add(bufferLayout(compiler, resource, false, stack));
            }
            final List<UniformBuffer> storageLayouts = new ArrayList<>();
            for (Resource resource : storageBuffers) {
                storageLayouts.add(bufferLayout(compiler, resource, true, stack));
            }
            final List<StageIo> stageInputs = stageIo(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, stack);
            final List<StageIo> stageOutputs = stageIo(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, stack);

            final IntBuffer dummySampler = stack.mallocInt(1);
            check(Spvc.spvc_compiler_build_dummy_sampler_for_combined_images(compiler, dummySampler),
                    "spvc_compiler_build_dummy_sampler_for_combined_images");
            check(Spvc.spvc_compiler_build_combined_image_samplers(compiler),
                    "spvc_compiler_build_combined_image_samplers");
            final List<CombinedSampler> combinedSamplers = combinedSamplers(compiler, dummySampler.get(0), stack);

            final int sizeX = Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                    compiler, Spv.SpvExecutionModeLocalSize, 0
            );
            final int sizeY = Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                    compiler, Spv.SpvExecutionModeLocalSize, 1
            );
            final int sizeZ = Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                    compiler, Spv.SpvExecutionModeLocalSize, 2
            );
            return new Reflection(
                    new int[]{sizeX, sizeY, sizeZ},
                    capabilities(spirv),
                    uniformBuffers,
                    storageBuffers,
                    sampledImages,
                    separateImages,
                    separateSamplers,
                    storageImages,
                    combinedSamplers,
                    layouts,
                    storageLayouts,
                    stageInputs,
                    stageOutputs
            );
        }
    }

    private static List<Resource> resources(
            final long compiler,
            final long resources,
            final int type,
            final MemoryStack stack
    ) {
        final org.lwjgl.PointerBuffer listOut = stack.mallocPointer(1);
        final org.lwjgl.PointerBuffer countOut = stack.mallocPointer(1);
        check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, listOut, countOut),
                "spvc_resources_get_resource_list_for_type(" + type + ")");
        final long listAddress = listOut.get(0);
        final int count = Math.toIntExact(countOut.get(0));
        if (count == 0 || listAddress == MemoryUtil.NULL) {
            return List.of();
        }
        final SpvcReflectedResource.Buffer entries = SpvcReflectedResource.create(listAddress, count);
        final List<Resource> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final SpvcReflectedResource reflected = entries.get(i);
            final int id = reflected.id();
            final String reflectedName = reflected.nameString();
            final String name = (type == Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER
                    || type == Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER)
                    && reflectedName != null && !reflectedName.isBlank()
                    && !reflectedName.startsWith("type.") && !reflectedName.startsWith("$")
                    ? reflectedName
                    : name(compiler, id, reflectedName);
            final int typeId = reflected.type_id();
            final int baseTypeId = reflected.base_type_id();
            final int baseType = Spvc.spvc_type_get_basetype(
                    Spvc.spvc_compiler_get_type_handle(compiler, baseTypeId)
            );
            final long imageType = imageType(compiler, typeId, baseTypeId, baseType);
            final int imageDimension = imageType == 0L ? -1 : Spvc.spvc_type_get_image_dimension(imageType);
            final boolean imageArrayed = imageType != 0L && Spvc.spvc_type_get_image_arrayed(imageType);
            final boolean imageDepth = imageType != 0L && Spvc.spvc_type_get_image_is_depth(imageType);
            final int imageStorageFormat = imageType == 0L
                    ? Spv.SpvImageFormatUnknown
                    : Spvc.spvc_type_get_image_storage_format(imageType);
            final int imageAccess = imageType == 0L
                    ? Spv.SpvAccessQualifierReadOnly
                    : Spvc.spvc_type_get_image_access_qualifier(imageType);
            result.add(new Resource(
                    name,
                    id,
                    typeId,
                    baseTypeId,
                    decoration(compiler, id, Spv.SpvDecorationDescriptorSet, 0),
                    decoration(compiler, id, Spv.SpvDecorationBinding, -1),
                    baseType,
                    arrayDimensions(compiler, Spvc.spvc_compiler_get_type_handle(compiler, typeId), stack),
                    imageDimension,
                    imageArrayed,
                    imageDepth,
                    imageStorageFormat,
                    imageAccess,
                    resourceHasAccessDecoration(compiler, id, baseTypeId, type, Spv.SpvDecorationNonWritable),
                    resourceHasAccessDecoration(compiler, id, baseTypeId, type, Spv.SpvDecorationNonReadable),
                    imageType == 0L ? Spvc.SPVC_BASETYPE_UNKNOWN : Spvc.spvc_type_get_basetype(
                            Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_image_sampled_type(imageType)))
            ));
        }
        return result;
    }

    private static boolean resourceHasAccessDecoration(final long compiler, final int id, final int baseTypeId,
                                                        final int resourceType, final int decoration) {
        if (Spvc.spvc_compiler_has_decoration(compiler, id, decoration)) return true;
        if (resourceType != Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER) return false;
        // DXC can declare read-only buffers by decorating their block members instead of the variable.
        final long block = Spvc.spvc_compiler_get_type_handle(compiler, baseTypeId);
        final int count = Spvc.spvc_type_get_num_member_types(block);
        for (int i = 0; i < count; i++) {
            if (!Spvc.spvc_compiler_has_member_decoration(compiler, baseTypeId, i, decoration)) return false;
        }
        return count > 0;
    }

    private static List<StageIo> stageIo(
            final long compiler,
            final long resources,
            final int type,
            final MemoryStack stack
    ) {
        final org.lwjgl.PointerBuffer listOut = stack.mallocPointer(1);
        final org.lwjgl.PointerBuffer countOut = stack.mallocPointer(1);
        check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, listOut, countOut),
                "spvc_resources_get_resource_list_for_type(stage " + type + ")");
        final long listAddress = listOut.get(0);
        final int count = Math.toIntExact(countOut.get(0));
        if (count == 0 || listAddress == MemoryUtil.NULL) {
            return List.of();
        }
        final SpvcReflectedResource.Buffer entries = SpvcReflectedResource.create(listAddress, count);
        final List<StageIo> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final SpvcReflectedResource reflected = entries.get(i);
            final int id = reflected.id();
            final int typeId = reflected.type_id();
            final int baseTypeId = reflected.base_type_id();
            final long stageType = Spvc.spvc_compiler_get_type_handle(compiler, typeId);
            final long baseType = Spvc.spvc_compiler_get_type_handle(compiler, baseTypeId);
            final int location = decoration(compiler, id, Spv.SpvDecorationLocation, -1);
            result.add(new StageIo(
                    name(compiler, id, reflected.nameString()),
                    location,
                    describeType(stageType),
                    scalarName(
                            Spvc.spvc_type_get_basetype(baseType),
                            Spvc.spvc_type_get_bit_width(baseType)
                    ),
                    Spvc.spvc_type_get_bit_width(baseType),
                    Math.max(1, Spvc.spvc_type_get_vector_size(stageType)),
                    Math.max(1, Spvc.spvc_type_get_columns(stageType)),
                    arrayDimensions(compiler, stageType, stack)
            ));
        }
        return List.copyOf(result);
    }

    private static long imageType(
            final long compiler,
            final int typeId,
            final int baseTypeId,
            final int baseType
    ) {
        if (baseType == Spvc.SPVC_BASETYPE_IMAGE || baseType == Spvc.SPVC_BASETYPE_SAMPLED_IMAGE) {
            return Spvc.spvc_compiler_get_type_handle(compiler, baseTypeId);
        }
        final long type = Spvc.spvc_compiler_get_type_handle(compiler, typeId);
        final int typeBase = Spvc.spvc_type_get_basetype(type);
        return typeBase == Spvc.SPVC_BASETYPE_IMAGE || typeBase == Spvc.SPVC_BASETYPE_SAMPLED_IMAGE
                ? type
                : 0L;
    }

    private static int[] arrayDimensions(final long compiler, final long type, final MemoryStack stack) {
        final int count = Spvc.spvc_type_get_num_array_dimensions(type);
        if (count <= 0) {
            return new int[0];
        }
        final int[] dimensions = new int[count];
        for (int i = 0; i < count; i++) {
            dimensions[i] = Spvc.spvc_type_get_array_dimension(type, i);
        }
        return dimensions;
    }

    private static List<CombinedSampler> combinedSamplers(final long compiler, final int dummySamplerId, final MemoryStack stack) {
        final org.lwjgl.PointerBuffer listOut = stack.mallocPointer(1);
        final org.lwjgl.PointerBuffer countOut = stack.mallocPointer(1);
        check(Spvc.spvc_compiler_get_combined_image_samplers(compiler, listOut, countOut),
                "spvc_compiler_get_combined_image_samplers");
        final long listAddress = listOut.get(0);
        final int count = Math.toIntExact(countOut.get(0));
        if (count == 0 || listAddress == MemoryUtil.NULL) {
            return List.of();
        }
        final SpvcCombinedImageSampler.Buffer entries = SpvcCombinedImageSampler.create(listAddress, count);
        final List<CombinedSampler> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final SpvcCombinedImageSampler sampler = entries.get(i);
            result.add(new CombinedSampler(
                    sampler.combined_id(),
                    sampler.image_id(),
                    sampler.sampler_id() == dummySamplerId ? -1 : sampler.sampler_id(),
                    rawName(compiler, sampler.combined_id()),
                    rawName(compiler, sampler.image_id()),
                    sampler.sampler_id() == dummySamplerId ? null : rawName(compiler, sampler.sampler_id())
            ));
        }
        return result;
    }

    private static UniformBuffer bufferLayout(
            final long compiler,
            final Resource resource,
            final boolean storage,
            final MemoryStack stack
    ) {
        final long structType = Spvc.spvc_compiler_get_type_handle(compiler, resource.baseTypeId());
        if (Spvc.spvc_type_get_basetype(structType) != Spvc.SPVC_BASETYPE_STRUCT) {
            return new UniformBuffer(
                    resource.name(),
                    resource.id(),
                    resource.descriptorSet(),
                    resource.descriptorBinding(),
                    0,
                    List.of()
            );
        }
        final org.lwjgl.PointerBuffer sizeOut = stack.mallocPointer(1);
        int result = Spvc.spvc_compiler_get_declared_struct_size(compiler, structType, sizeOut);
        if (result != 0 && storage) {
            result = Spvc.spvc_compiler_get_declared_struct_size_runtime_array(
                    compiler, structType, 1L, sizeOut
            );
        }
        check(result, "spvc_compiler_get_declared_struct_size(" + resource.name() + ")");
        final int structSize = Math.toIntExact(sizeOut.get(0));
        final List<Member> members = structMembers(
                compiler, resource.baseTypeId(), structType, resource.name(), 0, storage, stack
        );
        return new UniformBuffer(
                resource.name(),
                resource.id(),
                resource.descriptorSet(),
                resource.descriptorBinding(),
                structSize,
                !storage && resource.name().equals("ViewUniformBuffer")
                        ? activeViewMembers(compiler, resource, members, stack)
                        : members
        );
    }

    private static List<Member> activeViewMembers(final long compiler, final Resource resource,
                                                   final List<Member> members, final MemoryStack stack) {
        // The generated UE View cbuffer keeps its full layout even when an entry point reads only a few members.
        final var rangesOut = stack.mallocPointer(1);
        final var countOut = stack.mallocPointer(1);
        check(Spvc.spvc_compiler_get_active_buffer_ranges(compiler, resource.id(), rangesOut, countOut),
                "spvc_compiler_get_active_buffer_ranges(" + resource.name() + ")");
        final int count = Math.toIntExact(countOut.get(0));
        if (count == 0) return members;
        final var ranges = SpvcBufferRange.create(rangesOut.get(0), count);
        final var active = new BitSet(members.size());
        for (int i = 0; i < count; i++) {
            final int index = ranges.get(i).index();
            if (index < 0 || index >= members.size()) {
                throw new BakeException("Invalid active member index for " + resource.name() + ": " + index);
            }
            active.set(index);
        }
        return active.stream().mapToObj(members::get).toList();
    }

    private static List<Member> structMembers(
            final long compiler,
            final int structTypeId,
            final long structType,
            final String owner,
            final int depth,
            final boolean storage,
            final MemoryStack stack
    ) {
        if (depth > 8) {
            throw new BakeException("SPIR-V uniform struct nesting is too deep for " + owner);
        }
        final int memberCount = Spvc.spvc_type_get_num_member_types(structType);
        final List<Member> members = new ArrayList<>(memberCount);
        final IntBuffer intOut = stack.mallocInt(1);
        final org.lwjgl.PointerBuffer sizeMemberOut = stack.mallocPointer(1);
        for (int i = 0; i < memberCount; i++) {
            final String memberName = Spvc.spvc_compiler_get_member_name(compiler, structTypeId, i);
            final int memberTypeId = Spvc.spvc_type_get_member_type(structType, i);
            final long memberType = Spvc.spvc_compiler_get_type_handle(compiler, memberTypeId);
            final int baseType = Spvc.spvc_type_get_basetype(memberType);
            final int bitWidth = Spvc.spvc_type_get_bit_width(memberType);
            final int vectorSize = Math.max(1, Spvc.spvc_type_get_vector_size(memberType));
            final int columns = Math.max(1, Spvc.spvc_type_get_columns(memberType));
            final int offset = memberOffset(compiler, structType, i, intOut);
            final int sizeResult = Spvc.spvc_compiler_get_declared_struct_member_size(
                    compiler, structType, i, sizeMemberOut
            );
            if (sizeResult != 0 && !storage) {
                throw new BakeException(
                        "spvc_compiler_get_declared_struct_member_size(" + owner + "[" + i + "]) failed"
                );
            }
            final int size = sizeResult == 0 ? Math.toIntExact(sizeMemberOut.get(0)) : 0;
            intOut.put(0, 0);
            final int arrayStride = Spvc.spvc_compiler_type_struct_member_array_stride(
                    compiler, structType, i, intOut
            ) == 0 ? intOut.get(0) : 0;
            intOut.put(0, 0);
            final int matrixStride = Spvc.spvc_compiler_type_struct_member_matrix_stride(
                    compiler, structType, i, intOut
            ) == 0 ? intOut.get(0) : 0;
            final boolean rowMajor = Spvc.spvc_compiler_has_member_decoration(
                    compiler, structTypeId, i, Spv.SpvDecorationRowMajor
            );
            final boolean colMajor = Spvc.spvc_compiler_has_member_decoration(
                    compiler, structTypeId, i, Spv.SpvDecorationColMajor
            );
            final String safeName = memberName == null ? "member" + i : memberName;
            final List<Member> nested = baseType == Spvc.SPVC_BASETYPE_STRUCT
                    ? structMembers(compiler, memberTypeId, memberType, owner + "." + safeName, depth + 1, storage, stack)
                    : List.of();
            members.add(new Member(
                    safeName,
                    describeType(memberType),
                    scalarName(baseType, bitWidth),
                    bitWidth,
                    vectorSize,
                    columns,
                    arrayDimensions(compiler, memberType, stack),
                    offset,
                    size,
                    arrayStride,
                    matrixStride,
                    rowMajor ? "row-major" : (colMajor ? "column-major" : "unspecified"),
                    rowMajor,
                    nested
            ));
        }
        return List.copyOf(members);
    }

    private static int memberOffset(
            final long compiler,
            final long structType,
            final int index,
            final IntBuffer out
    ) {
        final int result = Spvc.spvc_compiler_type_struct_member_offset(compiler, structType, index, out);
        if (result != 0) {
            throw new BakeException("SPIR-V struct member offset reflection failed at index " + index);
        }
        return out.get(0);
    }

    private static String describeType(final long type) {
        final int base = Spvc.spvc_type_get_basetype(type);
        final int width = Spvc.spvc_type_get_bit_width(type);
        final int vector = Math.max(1, Spvc.spvc_type_get_vector_size(type));
        final int columns = Math.max(1, Spvc.spvc_type_get_columns(type));
        final String scalar = scalarName(base, width);
        final StringBuilder text = new StringBuilder();
        if (columns > 1) {
            text.append(matrixPrefix(base)).append(columns);
            if (vector > 1) {
                text.append('x').append(vector);
            }
        } else if (vector > 1) {
            text.append(vectorPrefix(base)).append(vector);
        } else {
            text.append(scalar);
        }
        final int dimensions = Spvc.spvc_type_get_num_array_dimensions(type);
        for (int i = 0; i < dimensions; i++) {
            final int dimension = Spvc.spvc_type_get_array_dimension(type, i);
            text.append(dimension == 0 ? "[]" : "[" + dimension + "]");
        }
        return text.toString();
    }

    private static String vectorPrefix(final int base) {
        return switch (base) {
            case Spvc.SPVC_BASETYPE_BOOLEAN -> "bvec";
            case Spvc.SPVC_BASETYPE_INT8 -> "i8vec";
            case Spvc.SPVC_BASETYPE_UINT8 -> "u8vec";
            case Spvc.SPVC_BASETYPE_INT16 -> "i16vec";
            case Spvc.SPVC_BASETYPE_UINT16 -> "u16vec";
            case Spvc.SPVC_BASETYPE_INT32 -> "ivec";
            case Spvc.SPVC_BASETYPE_UINT32 -> "uvec";
            case Spvc.SPVC_BASETYPE_INT64 -> "i64vec";
            case Spvc.SPVC_BASETYPE_UINT64 -> "u64vec";
            case Spvc.SPVC_BASETYPE_FP16 -> "f16vec";
            case Spvc.SPVC_BASETYPE_FP32 -> "vec";
            case Spvc.SPVC_BASETYPE_FP64 -> "dvec";
            case Spvc.SPVC_BASETYPE_STRUCT -> "structvec";
            default -> "vec";
        };
    }

    private static String matrixPrefix(final int base) {
        return switch (base) {
            case Spvc.SPVC_BASETYPE_FP16 -> "f16mat";
            case Spvc.SPVC_BASETYPE_FP32 -> "mat";
            case Spvc.SPVC_BASETYPE_FP64 -> "dmat";
            default -> "mat";
        };
    }

    private static String scalarName(final int base, final int bits) {
        return switch (base) {
            case Spvc.SPVC_BASETYPE_BOOLEAN -> "bool";
            case Spvc.SPVC_BASETYPE_INT8 -> "int8";
            case Spvc.SPVC_BASETYPE_UINT8 -> "uint8";
            case Spvc.SPVC_BASETYPE_INT16 -> "int16";
            case Spvc.SPVC_BASETYPE_UINT16 -> "uint16";
            case Spvc.SPVC_BASETYPE_INT32 -> "int";
            case Spvc.SPVC_BASETYPE_UINT32 -> "uint";
            case Spvc.SPVC_BASETYPE_INT64 -> "int64";
            case Spvc.SPVC_BASETYPE_UINT64 -> "uint64";
            case Spvc.SPVC_BASETYPE_FP16 -> "half";
            case Spvc.SPVC_BASETYPE_FP32 -> "float";
            case Spvc.SPVC_BASETYPE_FP64 -> "double";
            case Spvc.SPVC_BASETYPE_STRUCT -> "struct";
            case Spvc.SPVC_BASETYPE_IMAGE -> "image";
            case Spvc.SPVC_BASETYPE_SAMPLED_IMAGE -> "sampledImage";
            case Spvc.SPVC_BASETYPE_SAMPLER -> "sampler";
            default -> "base" + base + "x" + bits;
        };
    }

    private static int decoration(final long compiler, final int id, final int decoration, final int fallback) {
        return Spvc.spvc_compiler_has_decoration(compiler, id, decoration)
                ? Spvc.spvc_compiler_get_decoration(compiler, id, decoration)
                : fallback;
    }

    private static String name(final long compiler, final int id, final String fallback) {
        final String value = Spvc.spvc_compiler_get_name(compiler, id);
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        throw new BakeException("SPIR-V resource id " + id + " has no name");
    }

    private static String rawName(final long compiler, final int id) {
        final String value = Spvc.spvc_compiler_get_name(compiler, id);
        return value == null || value.isBlank() ? null : value;
    }

    private static Set<Integer> capabilities(final byte[] spirv) {
        final Set<Integer> result = new LinkedHashSet<>();
        final ByteBuffer bytes = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        bytes.getInt();
        bytes.getInt();
        bytes.getInt();
        bytes.getInt();
        bytes.getInt();
        while (bytes.remaining() >= 4) {
            final int word = bytes.getInt();
            final int opcode = word & 0xffff;
            final int wordCount = (word >>> 16) & 0xffff;
            if (wordCount == 0) {
                throw new BakeException("Malformed SPIR-V instruction stream");
            }
            if (opcode == 17 && wordCount >= 2) {
                result.add(bytes.getInt());
                bytes.position(bytes.position() + (wordCount - 2) * 4);
            } else {
                bytes.position(bytes.position() + (wordCount - 1) * 4);
            }
            if (bytes.position() > bytes.limit()) {
                throw new BakeException("Truncated SPIR-V instruction stream");
            }
        }
        return result;
    }

    private static void check(final int result, final String operation) {
        if (result != 0) {
            throw new BakeException("SPIRV-Cross " + operation + " failed with result " + result);
        }
    }
}
