package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DxcCompiler {
    private static final int SPIRV_MAGIC = 0x07230203;

    private final Path dxc;
    private final Duration timeout;

    DxcCompiler(final Path dxc, final Duration timeout) {
        this.dxc = dxc.toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    byte[] compileCompute(
            final Path source,
            final Path output,
            final String entrypoint,
            final Map<String, String> defines,
            final List<Path> includeDirectories,
            final List<String> extraArguments,
            final boolean enable16BitTypes,
            final Path processDirectory
    ) {
        return compile(source, output, entrypoint, ShaderStage.COMPUTE, defines, includeDirectories,
                extraArguments, enable16BitTypes, processDirectory);
    }

    byte[] compile(
            final Path source,
            final Path output,
            final String entrypoint,
            final ShaderStage stage,
            final Map<String, String> defines,
            final List<Path> includeDirectories,
            final List<String> extraArguments,
            final boolean enable16BitTypes,
            final Path processDirectory
    ) {
        final List<String> command = new ArrayList<>();
        command.add(this.dxc.toString());
        command.add("-spirv");
        command.add("-T");
        command.add(stage.profile());
        command.add("-E");
        command.add(entrypoint);
        command.add("-HV");
        command.add("2021");
        command.add("-WX");
        command.add("-O3");
        command.add("-fvk-use-gl-layout");
        command.add("-fspv-target-env=vulkan1.2");
        if (enable16BitTypes) {
            command.add("-enable-16bit-types");
        }
        for (Path include : includeDirectories) {
            command.add("-I");
            command.add(include.toString());
        }
        for (Map.Entry<String, String> define : defines.entrySet()) {
            command.add("-D" + define.getKey() + "=" + define.getValue());
        }
        command.addAll(extraArguments);
        command.add("-Fo");
        command.add(output.toString());
        command.add(source.toString());

        final ProcessRunner.Result result = ProcessRunner.run(command, processDirectory, this.timeout, null);
        if (result.exitCode() != 0) {
            throw new BakeException(result.describeFailure("DXC compile of " + source.getFileName()));
        }
        if (!Files.isRegularFile(output)) {
            throw new BakeException("DXC did not produce SPIR-V for " + source + ": " + output);
        }
        final byte[] spirv;
        try {
            spirv = Files.readAllBytes(output);
        } catch (IOException error) {
            throw new BakeException("Unable to read DXC output " + output, error);
        }
        validateSpirv(spirv, "DXC output " + output);
        return ViewUniformLayout.compact(spirv);
    }

    static void validateSpirv(final byte[] spirv, final String description) {
        if (spirv == null || spirv.length < 20 || (spirv.length & 3) != 0) {
            throw new BakeException(description + " is not a SPIR-V binary (length " + (spirv == null ? 0 : spirv.length) + ")");
        }
        final int magic = (spirv[0] & 0xff)
                | ((spirv[1] & 0xff) << 8)
                | ((spirv[2] & 0xff) << 16)
                | ((spirv[3] & 0xff) << 24);
        if (magic != SPIRV_MAGIC) {
            throw new BakeException(description + " has invalid SPIR-V magic 0x" + Integer.toHexString(magic));
        }
    }
}
