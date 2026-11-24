package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserves R8_UINT truncation when a Niagara byte array uses a 32-bit storage buffer. */
final class NiagaraArrayByteBufferLowering {
    private NiagaraArrayByteBufferLowering() { }

    static void apply(Path root, List<String> names) throws IOException {
        Path source = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        for (String name : names) {
            Pattern write = Pattern.compile("\\b(" + Pattern.quote(name) + "\\s*\\[\\s*Index\\s*\\]\\s*=\\s*)Value\\s*;");
            Matcher matcher = write.matcher(text);
            boolean found = matcher.find();
            if (found) text = matcher.replaceAll("$1(Value & 255);");
            Pattern scope = Pattern.compile("#\\s*define\\h+BUFFER_NAME\\h+" + Pattern.quote(name)
                    + "\\b[\\s\\S]*?#\\s*undef\\h+BUFFER_NAME");
            matcher = scope.matcher(text);
            StringBuffer rewritten = new StringBuffer();
            while (matcher.find()) {
                String block = matcher.group();
                String original = "BUFFER_NAME[Index] = Value;";
                if (block.contains(original)) {
                    found = true;
                    block = block.replace(original, "BUFFER_NAME[Index] = (Value & 255);");
                }
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(block));
            }
            matcher.appendTail(rewritten);
            text = rewritten.toString();
            if (!found) throw new BakeException("Niagara byte array write is missing: " + name);
        }
        Files.writeString(source, text, StandardCharsets.UTF_8);
    }
}
