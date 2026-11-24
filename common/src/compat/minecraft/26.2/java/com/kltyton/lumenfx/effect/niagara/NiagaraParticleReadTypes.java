package com.kltyton.lumenfx.effect.niagara;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Niagara particle-reader value types and compatible dataset storage. */
public final class NiagaraParticleReadTypes {
    private NiagaraParticleReadTypes() { }

    public static int width(String type) {
        return switch (type) {
            case "Int", "Bool", "Float" -> 1;
            case "Vector 2D", "ID" -> 2;
            case "Vector", "Position" -> 3;
            case "Vector 4", "Color", "Quaternion" -> 4;
            default -> 0;
        };
    }

    public static boolean compatible(NiagaraDefinition.Attribute attribute, String type) {
        return exactOrHalf(attribute, type) || type.equals("Vector") && exactOrHalf(attribute, "Position");
    }

    private static boolean exactOrHalf(NiagaraDefinition.Attribute attribute, String type) {
        if (attribute.count() != width(type)) return false;
        String source = attribute.sourceType();
        if (attribute.type() == 2) {
            String half = switch (type) {
                case "Float" -> "NiagaraHalf";
                case "Vector 2D" -> "NiagaraHalfVector2";
                case "Vector", "Position" -> "NiagaraHalfVector3";
                case "Vector 4", "Color", "Quaternion" -> "NiagaraHalfVector4";
                default -> "";
            };
            return !half.isEmpty() && source.endsWith("." + half);
        }
        return switch (type) {
            case "Int" -> attribute.type() == 1 && source.endsWith(".NiagaraInt32");
            case "Bool" -> attribute.type() == 1 && source.endsWith(".NiagaraBool");
            case "ID" -> attribute.type() == 1 && source.endsWith(".NiagaraID");
            case "Float" -> attribute.type() == 0 && source.endsWith(".NiagaraFloat");
            case "Vector 2D" -> attribute.type() == 0 && source.endsWith(".Vector2f");
            case "Vector" -> attribute.type() == 0 && source.endsWith(".Vector3f");
            case "Position" -> attribute.type() == 0 && source.endsWith(".NiagaraPosition");
            case "Vector 4" -> attribute.type() == 0 && source.endsWith(".Vector4f");
            case "Color" -> attribute.type() == 0 && source.endsWith(".LinearColor");
            case "Quaternion" -> attribute.type() == 0 && source.endsWith(".Quat4f");
            default -> false;
        };
    }

    public static NiagaraDefinition.@Nullable Attribute resolve(List<NiagaraDefinition.Attribute> attributes, String name, String type) {
        String bare = bare(name);
        NiagaraDefinition.Attribute half = null;
        for (var attribute : attributes) {
            if (!SourceNames.equal(bare(attribute.name()), bare) || !compatible(attribute, type)) continue;
            if (attribute.type() != 2) return attribute;
            if (half == null) half = attribute;
        }
        return half;
    }

    /** The GPU shader-storage binder resolves the first matching dataset FName before validating its type. */
    public static NiagaraDefinition.@Nullable Attribute gpu(List<NiagaraDefinition.Attribute> attributes, String name, String type) {
        if (SourceNames.equal(name, "None")) return null;
        for (var attribute : attributes) {
            if (SourceNames.equal(attribute.name(), name)) return compatible(attribute, type) ? attribute : null;
        }
        return null;
    }

    /** Compiled renderer accessors prefer the exact type, then the legacy Position alias, then compressed storage. */
    public static NiagaraDefinition.@Nullable Attribute renderer(List<NiagaraDefinition.Attribute> attributes, @Nullable String name,
                                                                 String type) {
        if (name == null || SourceNames.equal(name, "None")) return null;
        String bare = bare(name);
        NiagaraDefinition.Attribute alternate = null, half = null;
        for (var attribute : attributes) {
            if (!SourceNames.equal(bare(attribute.name()), bare)) continue;
            if (exactOrHalf(attribute, type)) {
                if (attribute.type() != 2) return attribute;
                if (half == null) half = attribute;
            } else if (alternate == null && type.equals("Position") && exactOrHalf(attribute, "Vector")) {
                alternate = attribute;
            }
        }
        return alternate != null ? alternate : half;
    }

    public static NiagaraDefinition.@Nullable Attribute ribbonId(List<NiagaraDefinition.Attribute> attributes,
                                                                NiagaraDefinition.Renderer renderer) {
        var binding = renderer.attributeBindings().get("RibbonID");
        if (binding != null && !binding.particle()) return null;
        String name = binding == null ? renderer.ribbonIdBinding() : binding.name();
        String type = binding == null ? null : binding.sourceType();
        if (type != null) return switch (type) {
            case "/Script/Niagara.NiagaraID" -> renderer(attributes, name, "ID");
            case "/Script/Niagara.NiagaraInt32" -> renderer(attributes, name, "Int");
            default -> null;
        };
        var id = renderer(attributes, name, "ID");
        return id != null ? id : renderer(attributes, name, "Int");
    }

    private static String bare(String name) {
        return name.regionMatches(true, 0, "Particles.", 0, "Particles.".length()) ? name.substring("Particles.".length()) : name;
    }
}
