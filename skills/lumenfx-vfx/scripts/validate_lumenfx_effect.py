#!/usr/bin/env python3
"""
Deterministic LumenFX Effect JSON, GLSL Shader & Performance Budget Validator (Zero Dependencies).

Validates LumenFX format_version: 1 effects against exact parser contracts from:
- Minecraft 26.2 / 26.3 strict Blaze3D parser (EffectJson.java, EffectEmitterJson.java,
  EffectJsonFields.java, MeshRendererJson.java, EffectResources.java)
- Minecraft 1.20.1 - 26.1.2 OpenGL direct parser (ProductionEffectParser.java,
  DirectGlCustomMaterialCompiler.java)

Also checks referenced custom .fsh shaders & textures (when --assets-root is supplied)
and enforces VFX visual-contrast & multi-instance performance budgets.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

VALID_BLENDS = {
    "add",
    "additive",
    "soft_additive",
    "alpha",
    "premultiplied",
    "opaque",
    "holdout",
    "modulate",
    "colored_transmittance",
}
VALID_BLEND_OPS = {"add", "subtract", "reverse_subtract", "min", "max"}
VALID_DEPTH_COMPARES = {
    "disabled",
    "never",
    "less",
    "equal",
    "less_equal",
    "greater",
    "not_equal",
    "greater_equal",
    "always",
}
VALID_TRANSLUCENCY_PASSES = {"before_dof", "after_dof", "after_motion_blur"}
VALID_RENDERERS = {
    "sprite",
    "ribbon",
    "beam",
    "mesh",
    "light",
    "decal",
    "volume",
    "splat",
    "component",
    "simulation",
}
RENDERERS_USING_MATERIAL = {"sprite", "ribbon", "beam", "mesh", "decal", "volume"}
VALID_SHAPES = {
    "point",
    "box",
    "sphere",
    "cylinder",
    "cone",
    "circle",
    "hemisphere",
    "edge",
    "box_edge",
    "donut",
    "mesh",
}
VALID_NOISE_MODES = {"displacement", "vector_field"}
VALID_INTEGRATORS = {"analytic", "niagara"}
VALID_UV_MODES = {"stretch", "distance"}
VALID_SORT_MODES = {"none", "distance", "view_depth", "custom_ascending", "custom_descending"}
VALID_INTERPOLATIONS = {"constant", "linear", "cubic"}
VALID_WRAPS = {"clamp", "loop", "ping_pong"}
VALID_RIBBON_SHAPES = {"Plane", "MultiPlane", "Tube", "Custom"}
VALID_RIBBON_FACINGS = {"Screen", "Custom", "CustomSideVector"}
VALID_RIBBON_DIRECTIONS = {"FrontToBack", "BackToFront"}
VALID_RIBBON_EDGES = {"Locked", "SmoothTransition"}

BUILTIN_SHADERS = {
    "lumenfx:core/effect",
    "lumenfx:core/color",
    "lumenfx:core/effect_premultiplied",
    "lumenfx:core/color_premultiplied",
    "lumenfx:core/unity_particle",
    "lumenfx:scene/surface/opaque_particle_unlit",
}

TIERS = {
    "ambient": {"max_particles": 120, "max_emitters": 4, "max_lights": 0, "max_overdraw": 80.0},
    "combat": {"max_particles": 600, "max_emitters": 8, "max_lights": 1, "max_overdraw": 350.0},
    "boss": {"max_particles": 1200, "max_emitters": 16, "max_lights": 3, "max_overdraw": 700.0},
    "showcase": {"max_particles": 4000, "max_emitters": 32, "max_lights": 8, "max_overdraw": 2500.0},
}

ID_RE = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
SNIPPET_SYMBOLS_RE = re.compile(r"\b(vUv|vColor|vWorldPos|uTime|uTex|uTex1|uParams|lfx_soft_particle_alpha)\b")


@dataclass
class Finding:
    severity: str  # ERROR | WARN
    code: str
    location: str
    message: str


def _is_finite_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value))


def _estimate_scalar_max(value: Any, default: float = 0.0) -> float:
    if _is_finite_number(value):
        return float(value)
    if isinstance(value, dict):
        if "min" in value or "max" in value:
            return max(_estimate_scalar_max(value.get("min"), default), _estimate_scalar_max(value.get("max"), default))
        if isinstance(value.get("keys"), list) and value["keys"]:
            vals = [float(k["value"]) for k in value["keys"] if isinstance(k, dict) and _is_finite_number(k.get("value"))]
            return max(vals) if vals else default
        if isinstance(value.get("lookup"), dict) and isinstance(value["lookup"].get("values"), list):
            vals = [float(v) for v in value["lookup"]["values"] if _is_finite_number(v)]
            return max(vals) if vals else default
        if isinstance(value.get("multiply"), list) and value["multiply"]:
            prod = 1.0
            for f in value["multiply"]:
                prod *= _estimate_scalar_max(f, 1.0)
            return prod
        if isinstance(value.get("add"), list) and value["add"]:
            return sum(_estimate_scalar_max(f, 0.0) for f in value["add"])
    return default


def _validate_curve(value: Any, loc: str, findings: list[Finding]) -> None:
    if _is_finite_number(value):
        return
    if not isinstance(value, dict):
        findings.append(Finding("ERROR", "INVALID_CURVE", loc, "Expected finite number or curve object."))
        return
    if "lookup" in value:
        lookup = value["lookup"]
        if not isinstance(lookup, dict):
            findings.append(Finding("ERROR", "INVALID_LOOKUP", loc, "'lookup' must be an object."))
            return
        for req in ("minimum_time", "inverse_time_range"):
            if not _is_finite_number(lookup.get(req)):
                findings.append(Finding("ERROR", "INVALID_LOOKUP", f"{loc}.lookup.{req}", f"Missing finite number '{req}'."))
        vals = lookup.get("values")
        if not isinstance(vals, list) or not vals or not all(_is_finite_number(v) for v in vals):
            findings.append(Finding("ERROR", "INVALID_LOOKUP", f"{loc}.lookup.values", "'values' must be a non-empty array of numbers."))
        return
    keys = value.get("keys")
    if not isinstance(keys, list) or not keys:
        findings.append(Finding("ERROR", "INVALID_CURVE_KEYS", loc, "Curve object must contain a non-empty 'keys' array (or 'lookup')."))
        return
    for wrap_key in ("pre_wrap", "post_wrap"):
        if wrap_key in value and str(value[wrap_key]).lower() not in VALID_WRAPS:
            findings.append(Finding("ERROR", "INVALID_WRAP_MODE", f"{loc}.{wrap_key}", f"Invalid wrap mode '{value[wrap_key]}'; expected one of {sorted(VALID_WRAPS)}."))
    for idx, k in enumerate(keys):
        kloc = f"{loc}.keys[{idx}]"
        if not isinstance(k, dict):
            findings.append(Finding("ERROR", "INVALID_CURVE_KEY", kloc, "Curve key must be an object."))
            continue
        if not _is_finite_number(k.get("time")) or not _is_finite_number(k.get("value")):
            findings.append(Finding("ERROR", "INVALID_CURVE_KEY", kloc, "Curve key requires finite numeric 'time' and 'value'."))
        if "interpolation" in k and str(k["interpolation"]).lower() not in VALID_INTERPOLATIONS:
            findings.append(Finding("ERROR", "INVALID_INTERPOLATION", f"{kloc}.interpolation", f"Invalid interpolation '{k['interpolation']}'; expected one of {sorted(VALID_INTERPOLATIONS)}."))


def _validate_scalar(value: Any, loc: str, findings: list[Finding]) -> None:
    if _is_finite_number(value):
        return
    if not isinstance(value, dict):
        findings.append(Finding("ERROR", "INVALID_SCALAR", loc, "Expected finite number or ScalarFunction object."))
        return
    if "multiply" in value or "add" in value:
        op = "add" if "add" in value else "multiply"
        factors = value.get(op)
        if not isinstance(factors, list) or not factors:
            findings.append(Finding("ERROR", "INVALID_SCALAR_EXPR", f"{loc}.{op}", f"'{op}' must be a non-empty array of ScalarFunctions."))
            return
        for idx, item in enumerate(factors):
            _validate_scalar(item, f"{loc}.{op}[{idx}]", findings)
        return
    if "min" in value:
        if "max" not in value:
            findings.append(Finding("ERROR", "INCOMPLETE_RANGE", loc, "Random range object has 'min' without 'max'."))
            return
        _validate_curve(value["min"], f"{loc}.min", findings)
        _validate_curve(value["max"], f"{loc}.max", findings)
        return
    _validate_curve(value, loc, findings)


def _validate_vec3(value: Any, loc: str, findings: list[Finding]) -> None:
    if not isinstance(value, list) or len(value) != 3 or not all(_is_finite_number(v) for v in value):
        findings.append(Finding("ERROR", "INVALID_VEC3", loc, "Expected 3-element numeric vector [x, y, z]."))


def _validate_color(value: Any, loc: str, target: str, findings: list[Finding]) -> None:
    strict_262 = target in ("all", "26.2")
    if isinstance(value, dict):
        if "stops" in value:
            if strict_262:
                findings.append(
                    Finding(
                        "ERROR",
                        "COLOR_STOPS_UNSUPPORTED",
                        loc,
                        'Object {"stops": [...]} crashes EffectJsonFields.color on Minecraft 26.2/26.3 '
                        '(JsonParseException: Expected four color components). Use a 4-element [R, G, B, A] array '
                        'with per-channel {"keys": [...]} curves instead.',
                    )
                )
            else:
                stops = value.get("stops")
                if not isinstance(stops, list) or not stops:
                    findings.append(Finding("ERROR", "INVALID_COLOR_STOPS", loc, "'stops' must be a non-empty array."))
            return
        if "components" in value:
            value = value["components"]
        elif "random_time" in value:
            value = value["random_time"]
        else:
            findings.append(Finding("ERROR", "INVALID_COLOR", loc, "Expected 4-element [R, G, B, A] array (or object with 'components')."))
            return
    if not isinstance(value, list) or len(value) != 4:
        findings.append(Finding("ERROR", "INVALID_COLOR", loc, "Expected exactly 4 color components [R, G, B, A]."))
        return
    for idx, ch in enumerate(value):
        _validate_scalar(ch, f"{loc}[{idx}]", findings)


def _validate_shader_file(shader_id: str, ext: str, assets_root: Path, target: str, loc: str, findings: list[Finding]) -> None:
    if shader_id in BUILTIN_SHADERS:
        return
    ns, path = shader_id.split(":", 1)
    if ns == "lumenfx":
        return  # Built-in mod shader
    shader_path = assets_root / ns / "shaders" / f"{path}{ext}"
    if not shader_path.is_file():
        findings.append(Finding("ERROR", "MISSING_SHADER_FILE", loc, f"Shader '{shader_id}' not found at {shader_path}."))
        return
    if ext == ".fsh" and target in ("all", "26.2"):
        text = shader_path.read_text(encoding="utf-8", errors="replace").lstrip("\ufeff \t\r\n")
        if not text.startswith("#version"):
            findings.append(
                Finding(
                    "ERROR",
                    "SHADER_MISSING_VERSION",
                    loc,
                    f"Shader {shader_path.name} lacks a leading '#version 330' directive; headerless snippet shaders fail on Minecraft 26.2/26.3.",
                )
            )
        if SNIPPET_SYMBOLS_RE.search(text):
            findings.append(
                Finding(
                    "ERROR",
                    "SHADER_LEGACY_SNIPPET_SYMBOLS",
                    loc,
                    f"Shader {shader_path.name} uses opengl_direct snippet symbols (vUv/vColor/uTime/uTex/lfx_soft_particle_alpha) that do not exist on 26.2/26.3. Use effectUV, effectColor, EffectFrame, Sampler0 instead.",
                )
            )
        if "#moj_import <lumenfx:material_fog.glsl>" in text and re.search(r"^\s*in\s+vec3\s+effectPosition\s*;", text, flags=re.MULTILINE):
            findings.append(
                Finding(
                    "ERROR",
                    "DUPLICATE_EFFECT_POSITION",
                    loc,
                    f"Shader {shader_path.name} imports material_fog.glsl AND redeclares 'in vec3 effectPosition;' (already declared by material_fog.glsl).",
                )
            )


def _validate_mesh_block(mesh: Any, loc: str, target: str, findings: list[Finding]) -> None:
    if not isinstance(mesh, dict):
        findings.append(Finding("ERROR", "INVALID_MESH", loc, "'mesh' must be an object."))
        return
    entries = mesh.get("entries")
    if not isinstance(entries, list) or not entries:
        findings.append(Finding("ERROR", "INVALID_MESH_ENTRIES", f"{loc}.entries", "'mesh.entries' must be a non-empty array."))
        return
    if target not in ("all", "26.2"):
        return
    for req in ("facing_mode", "locked_axis_enabled", "locked_axis", "locked_axis_space", "bounds_scale", "frustum_culling", "cast_shadows"):
        if req not in mesh:
            findings.append(
                Finding(
                    "ERROR",
                    "INCOMPLETE_MESH_RENDERER_262",
                    f"{loc}.{req}",
                    f"Missing required 26.2 MeshRendererJson field '{req}' (causes NullPointerException on 26.2/26.3 reload).",
                )
            )
    for idx, entry in enumerate(entries):
        eloc = f"{loc}.entries[{idx}]"
        if not isinstance(entry, dict):
            findings.append(Finding("ERROR", "INVALID_MESH_ENTRY", eloc, "Mesh entry must be an object."))
            continue
        for req in ("materials", "scale", "rotation", "pivot", "pivot_space", "lod_mode", "lod_level", "lod_bias", "lod_distance_factor", "use_lod_range", "lod_range_min", "lod_range_max"):
            if req not in entry:
                findings.append(
                    Finding(
                        "ERROR",
                        "INCOMPLETE_MESH_ENTRY_262",
                        f"{eloc}.{req}",
                        f"Missing required 26.2 MeshRendererJson entry field '{req}'.",
                    )
                )
        if "rotation" in entry and (not isinstance(entry["rotation"], list) or len(entry["rotation"]) != 4):
            findings.append(Finding("ERROR", "INVALID_MESH_ROTATION", f"{eloc}.rotation", "Mesh rotation must be a 4-element quaternion [x, y, z, w]."))


def validate_effect(data: Any, source: str, target: str = "all", tier: str = "combat", assets_root: Path | None = None) -> tuple[list[Finding], dict[str, Any]]:
    findings: list[Finding] = []
    strict_262 = target in ("all", "26.2")
    limits = TIERS[tier]

    if not isinstance(data, dict):
        findings.append(Finding("ERROR", "INVALID_ROOT", source, "Effect JSON root must be an object."))
        return findings, {"source": source, "error_count": 1, "warn_count": 0}

    fv = data.get("format_version")
    if fv != 1:
        findings.append(Finding("ERROR", "INVALID_FORMAT_VERSION", "format_version", f"Expected integer 'format_version': 1 (got {fv!r})."))

    root_render = data.get("render") if isinstance(data.get("render"), dict) else {}
    root_hdr = bool(root_render.get("hdr", False))

    materials = data.get("materials")
    if not isinstance(materials, dict):
        sev = "ERROR" if strict_262 else "WARN"
        findings.append(Finding(sev, "MISSING_MATERIALS", "materials", "Root must contain a 'materials' object."))
        materials = {}

    emitters = data.get("emitters")
    if not isinstance(emitters, list):
        sev = "ERROR" if strict_262 else "WARN"
        findings.append(Finding(sev, "MISSING_EMITTERS", "emitters", "Root must contain an 'emitters' array."))
        emitters = []

    if strict_262:
        for legacy_root_key in ("lights", "volumes", "decals"):
            if legacy_root_key in data:
                findings.append(
                    Finding(
                        "WARN",
                        "LEGACY_ROOT_SECTION",
                        legacy_root_key,
                        f"Top-level '{legacy_root_key}' array is only parsed by opengl_direct (1.20.1-26.1.2) and ignored by 26.2/26.3 EffectJson.",
                    )
                )

    material_blends: dict[str, str] = {}
    for mat_name, mat in materials.items():
        mloc = f"materials.{mat_name}"
        if not isinstance(mat, dict):
            findings.append(Finding("ERROR", "INVALID_MATERIAL", mloc, "Material definition must be an object."))
            continue
        blend_raw = str(mat.get("blend", "alpha")).lower()
        material_blends[mat_name] = blend_raw
        if blend_raw == "multiply":
            sev = "ERROR" if strict_262 else "WARN"
            findings.append(Finding(sev, "INVALID_BLEND_MULTIPLY", f"{mloc}.blend", 'Blend "multiply" is NOT in MaterialDefinition.Blend on 26.2/26.3; use "modulate" instead.'))
        elif blend_raw not in VALID_BLENDS:
            findings.append(Finding("ERROR", "INVALID_BLEND", f"{mloc}.blend", f"Unknown blend '{blend_raw}'; valid values: {sorted(VALID_BLENDS)}."))

        if "blend_op" in mat and str(mat["blend_op"]).lower() not in VALID_BLEND_OPS:
            findings.append(Finding("ERROR", "INVALID_BLEND_OP", f"{mloc}.blend_op", f"Unknown blend_op '{mat['blend_op']}'; valid: {sorted(VALID_BLEND_OPS)}."))
        if "depth_compare" in mat and str(mat["depth_compare"]).lower() not in VALID_DEPTH_COMPARES:
            findings.append(Finding("ERROR", "INVALID_DEPTH_COMPARE", f"{mloc}.depth_compare", f"Unknown depth_compare '{mat['depth_compare']}'."))
        if "translucency_pass" in mat and str(mat["translucency_pass"]).lower() not in VALID_TRANSLUCENCY_PASSES:
            findings.append(Finding("ERROR", "INVALID_TRANSLUCENCY_PASS", f"{mloc}.translucency_pass", f"Unknown translucency_pass '{mat['translucency_pass']}'."))

        if "parameters" in mat:
            params = mat["parameters"]
            if not isinstance(params, list) or len(params) != 4 or not all(_is_finite_number(p) for p in params):
                findings.append(Finding("ERROR", "INVALID_PARAMETERS", f"{mloc}.parameters", "Material 'parameters' must be an array of exactly 4 finite numbers."))

        mat_render = mat.get("render") if isinstance(mat.get("render"), dict) else {}
        mat_hdr = bool(mat_render.get("hdr", root_hdr))
        if strict_262 and not mat_hdr and (mat.get("hdr") is True or _estimate_scalar_max(mat.get("bloom_intensity"), 0) > 0):
            findings.append(
                Finding(
                    "WARN",
                    "HDR_LEGACY_KEY_ONLY",
                    mloc,
                    'Top-level material "hdr"/"bloom_intensity" only affects 1.20.1-26.1.2. Add "render": {"hdr": true} (at root or material) to activate HDR on 26.2/26.3.',
                )
            )

        textures = mat.get("textures")
        if textures is not None:
            if not isinstance(textures, dict):
                findings.append(Finding("ERROR", "INVALID_TEXTURES", f"{mloc}.textures", "'textures' must be an object mapping SamplerName -> namespace:path."))
            else:
                for sampler, tex_id in textures.items():
                    tloc = f"{mloc}.textures.{sampler}"
                    if not isinstance(tex_id, str) or not ID_RE.match(tex_id):
                        findings.append(Finding("ERROR", "INVALID_TEXTURE_ID", tloc, f"Invalid Identifier '{tex_id}'."))
                    elif assets_root is not None:
                        ns, path = tex_id.split(":", 1)
                        if ns not in ("minecraft", "lumenfx"):
                            tex_file = assets_root / ns / path
                            if not tex_file.is_file():
                                findings.append(Finding("ERROR", "MISSING_TEXTURE_FILE", tloc, f"Texture '{tex_id}' not found at {tex_file}."))

        frag = mat.get("fragment_shader")
        if frag is not None:
            if not isinstance(frag, str) or not ID_RE.match(frag):
                findings.append(Finding("ERROR", "INVALID_SHADER_ID", f"{mloc}.fragment_shader", f"Invalid fragment_shader Identifier '{frag}'."))
            elif assets_root is not None:
                _validate_shader_file(frag, ".fsh", assets_root, target, f"{mloc}.fragment_shader", findings)

        vert = mat.get("vertex_material")
        if vert is not None:
            if not isinstance(vert, str) or not ID_RE.match(vert):
                findings.append(Finding("ERROR", "INVALID_VERTEX_SHADER_ID", f"{mloc}.vertex_material", f"Invalid vertex_material Identifier '{vert}'."))
            elif assets_root is not None:
                _validate_shader_file(vert, ".vsh", assets_root, target, f"{mloc}.vertex_material", findings)

    total_particles = 0
    additive_emitters = 0
    contrast_backing_emitters = 0
    visible_emitters = 0
    light_count = len(data.get("lights") or []) if isinstance(data.get("lights"), list) else 0
    overdraw_score = 0.0
    seen_names: set[str] = set()

    for idx, em in enumerate(emitters):
        eloc = f"emitters[{idx}]"
        if not isinstance(em, dict):
            findings.append(Finding("ERROR", "INVALID_EMITTER", eloc, "Emitter entry must be an object."))
            continue
        ename = em.get("name")
        if not isinstance(ename, str) or not ename.strip():
            findings.append(Finding("ERROR", "MISSING_EMITTER_NAME", f"{eloc}.name", "Emitter requires a non-empty string 'name'."))
            ename = f"emitter_{idx}"
        else:
            eloc = f"emitters[{idx}:{ename}]"
            if ename in seen_names:
                findings.append(Finding("WARN", "DUPLICATE_EMITTER_NAME", eloc, f"Duplicate emitter name '{ename}'."))
            seen_names.add(ename)

        renderer_raw = em.get("renderer")
        if not isinstance(renderer_raw, str) or renderer_raw.lower() not in VALID_RENDERERS:
            findings.append(Finding("ERROR", "INVALID_RENDERER", f"{eloc}.renderer", f"Invalid or missing renderer '{renderer_raw}'; valid: {sorted(VALID_RENDERERS)}."))
            renderer = "sprite"
        else:
            renderer = renderer_raw.lower()

        if renderer == "light":
            light_count += 1

        mat_ref = em.get("material", "")
        if renderer in RENDERERS_USING_MATERIAL:
            if not isinstance(mat_ref, str) or mat_ref not in materials:
                findings.append(Finding("ERROR", "UNKNOWN_MATERIAL_REF", f"{eloc}.material", f"Emitter references unknown material '{mat_ref}'."))

        if "max_particles" not in em:
            findings.append(Finding("WARN", "OMITTED_MAX_PARTICLES", f"{eloc}.max_particles", "Omitted 'max_particles' defaults to 4096; set an explicit tight cap."))
            mp = 4096
        else:
            mp_val = em["max_particles"]
            if not isinstance(mp_val, int) or isinstance(mp_val, bool) or mp_val < 0:
                findings.append(Finding("ERROR", "INVALID_MAX_PARTICLES", f"{eloc}.max_particles", "'max_particles' must be a non-negative integer."))
                mp = 64
            else:
                mp = mp_val
        total_particles += mp

        for sf in ("rate", "lifetime", "mass", "width", "height", "rotation", "angular_velocity", "start_speed", "drag", "size_over_life", "atlas_frame", "rate_over_distance"):
            if sf in em and em[sf] is not None:
                _validate_scalar(em[sf], f"{eloc}.{sf}", findings)

        for v3 in ("start_direction", "velocity_min", "velocity_max", "acceleration"):
            if v3 in em:
                _validate_vec3(em[v3], f"{eloc}.{v3}", findings)

        for v3s in ("velocity_over_life", "force_over_life"):
            if v3s in em:
                arr = em[v3s]
                if not isinstance(arr, list) or len(arr) != 3:
                    findings.append(Finding("ERROR", "INVALID_COMPONENT_COUNT", f"{eloc}.{v3s}", f"'{v3s}' must have exactly 3 scalar components."))
                else:
                    for ci, item in enumerate(arr):
                        _validate_scalar(item, f"{eloc}.{v3s}[{ci}]", findings)

        if "custom_data" in em:
            cd = em["custom_data"]
            if not isinstance(cd, list) or len(cd) != 4:
                findings.append(Finding("ERROR", "INVALID_CUSTOM_DATA", f"{eloc}.custom_data", "'custom_data' should be a 4-element scalar array."))
            else:
                for ci, item in enumerate(cd):
                    _validate_scalar(item, f"{eloc}.custom_data[{ci}]", findings)

        if "start_color" in em:
            _validate_color(em["start_color"], f"{eloc}.start_color", target, findings)
        if "color" in em:
            _validate_color(em["color"], f"{eloc}.color", target, findings)

        if "bursts" in em:
            bursts = em["bursts"]
            if not isinstance(bursts, list):
                findings.append(Finding("ERROR", "INVALID_BURSTS", f"{eloc}.bursts", "'bursts' must be an array."))
            else:
                for bi, b in enumerate(bursts):
                    bloc = f"{eloc}.bursts[{bi}]"
                    if not isinstance(b, dict):
                        findings.append(Finding("ERROR", "INVALID_BURST", bloc, "Burst item must be an object."))
                        continue
                    has_count = b.get("count") is not None
                    has_minmax = "min" in b or "max" in b
                    if has_count and has_minmax:
                        findings.append(Finding("ERROR", "BURST_MIXED_COUNT", bloc, "Burst cannot mix 'count' curve with 'min'/'max'."))
                    elif has_count:
                        _validate_scalar(b["count"], f"{bloc}.count", findings)
                    else:
                        bmin = b.get("min")
                        bmax = b.get("max")
                        if not isinstance(bmin, int) or not isinstance(bmax, int) or bmin < 0 or bmax < bmin:
                            findings.append(Finding("ERROR", "INVALID_BURST_RANGE", bloc, "Burst requires integers 0 <= min <= max."))
                    t = b.get("time", 0)
                    if not _is_finite_number(t) or float(t) < 0:
                        findings.append(Finding("ERROR", "INVALID_BURST_TIME", f"{bloc}.time", "Burst 'time' must be >= 0."))
                    cycles = b.get("cycles", 1)
                    interval = b.get("interval", 0)
                    if not isinstance(cycles, int) or cycles < 0 or not _is_finite_number(interval) or float(interval) < 0 or (cycles == 0 and float(interval) == 0):
                        findings.append(Finding("ERROR", "INVALID_BURST_CYCLES", bloc, "Invalid burst cycles/interval (cycles == 0 with interval == 0 throws)."))
                    prob = b.get("probability", 1)
                    if not _is_finite_number(prob) or not (0.0 <= float(prob) <= 1.0):
                        findings.append(Finding("ERROR", "INVALID_BURST_PROB", f"{bloc}.probability", "Burst 'probability' must be in [0, 1]."))

        if "shape" in em and em["shape"] is not None:
            shape = em["shape"]
            if not isinstance(shape, dict):
                findings.append(Finding("ERROR", "INVALID_SHAPE", f"{eloc}.shape", "'shape' must be an object."))
            else:
                stype = str(shape.get("type", "point")).lower()
                if stype not in VALID_SHAPES:
                    findings.append(Finding("ERROR", "INVALID_SHAPE_TYPE", f"{eloc}.shape.type", f"Unknown shape type '{stype}'; valid: {sorted(VALID_SHAPES)}."))
                if "size" in shape:
                    _validate_vec3(shape["size"], f"{eloc}.shape.size", findings)
                unity_shape = shape.get("unity") if isinstance(shape.get("unity"), dict) else {}
                if stype == "donut" and "donut" not in unity_shape:
                    findings.append(Finding("ERROR", "SHAPE_DONUT_MISSING_UNITY", f"{eloc}.shape", "Donut shape requires shape.unity.donut."))
                if stype == "mesh" and "mesh" not in unity_shape:
                    findings.append(Finding("ERROR", "SHAPE_MESH_MISSING_UNITY", f"{eloc}.shape", "Mesh shape requires shape.unity.mesh."))

        if "noise" in em and em["noise"] is not None:
            noise = em["noise"]
            if not isinstance(noise, dict):
                findings.append(Finding("ERROR", "INVALID_NOISE", f"{eloc}.noise", "'noise' must be an object."))
            else:
                nmode = str(noise.get("mode", "displacement")).lower()
                if nmode not in VALID_NOISE_MODES:
                    findings.append(Finding("ERROR", "INVALID_NOISE_MODE", f"{eloc}.noise.mode", f"Unknown noise mode '{nmode}'."))
                has_field = bool(noise.get("field"))
                if nmode == "vector_field" and not has_field:
                    findings.append(Finding("ERROR", "NOISE_VECTOR_FIELD_MISSING", f"{eloc}.noise", "noise.mode == 'vector_field' requires 'field'."))
                if nmode != "vector_field" and has_field:
                    findings.append(Finding("ERROR", "NOISE_FIELD_MODE_MISMATCH", f"{eloc}.noise", "noise.field is forbidden unless noise.mode == 'vector_field'."))
                if "strength" in noise:
                    _validate_vec3(noise["strength"], f"{eloc}.noise.strength", findings)
                if "scroll" in noise:
                    _validate_vec3(noise["scroll"], f"{eloc}.noise.scroll", findings)

        if "integrator" in em and str(em["integrator"]).lower() not in VALID_INTEGRATORS:
            findings.append(Finding("ERROR", "INVALID_INTEGRATOR", f"{eloc}.integrator", f"Unknown integrator '{em['integrator']}'."))
        if "uv_mode" in em and str(em["uv_mode"]).lower() not in VALID_UV_MODES:
            findings.append(Finding("ERROR", "INVALID_UV_MODE", f"{eloc}.uv_mode", f"Unknown uv_mode '{em['uv_mode']}'."))
        if "sort_mode" in em and str(em["sort_mode"]).lower() not in VALID_SORT_MODES:
            findings.append(Finding("ERROR", "INVALID_SORT_MODE", f"{eloc}.sort_mode", f"Unknown sort_mode '{em['sort_mode']}'."))

        if "ribbon" in em and em["ribbon"] is not None:
            rib = em["ribbon"]
            if not isinstance(rib, dict):
                findings.append(Finding("ERROR", "INVALID_RIBBON", f"{eloc}.ribbon", "'ribbon' must be an object."))
            else:
                if "shape" in rib and rib["shape"] not in VALID_RIBBON_SHAPES:
                    findings.append(Finding("ERROR", "INVALID_RIBBON_SHAPE", f"{eloc}.ribbon.shape", f"Case-sensitive ribbon.shape '{rib['shape']}' must be one of {sorted(VALID_RIBBON_SHAPES)}."))
                if "facing" in rib and rib["facing"] not in VALID_RIBBON_FACINGS:
                    findings.append(Finding("ERROR", "INVALID_RIBBON_FACING", f"{eloc}.ribbon.facing", f"Case-sensitive ribbon.facing '{rib['facing']}' must be one of {sorted(VALID_RIBBON_FACINGS)}."))
                if "draw_direction" in rib and rib["draw_direction"] not in VALID_RIBBON_DIRECTIONS:
                    findings.append(Finding("ERROR", "INVALID_RIBBON_DIRECTION", f"{eloc}.ribbon.draw_direction", f"Case-sensitive ribbon.draw_direction must be one of {sorted(VALID_RIBBON_DIRECTIONS)}."))
                for uv_key in ("uv0", "uv1"):
                    if isinstance(rib.get(uv_key), dict):
                        for edge_key in ("leading_edge", "trailing_edge"):
                            if edge_key in rib[uv_key] and rib[uv_key][edge_key] not in VALID_RIBBON_EDGES:
                                findings.append(Finding("ERROR", "INVALID_RIBBON_EDGE", f"{eloc}.ribbon.{uv_key}.{edge_key}", f"Case-sensitive edge mode must be one of {sorted(VALID_RIBBON_EDGES)}."))

        if renderer == "ribbon" and _estimate_scalar_max(em.get("subdivisions"), 1) > 6:
            findings.append(Finding("WARN", "HEAVY_RIBBON_TESSELLATION", f"{eloc}.subdivisions", f"Ribbon subdivisions={em.get('subdivisions')} is high; prefer 1-6."))

        if "mesh" in em and em["mesh"] is not None:
            _validate_mesh_block(em["mesh"], f"{eloc}.mesh", target, findings)

        if renderer in ("sprite", "ribbon", "beam", "mesh"):
            visible_emitters += 1
            blend = material_blends.get(str(mat_ref), "alpha")
            is_add = blend in ("add", "additive", "soft_additive")
            if is_add:
                additive_emitters += 1
            else:
                contrast_backing_emitters += 1
            w = max(_estimate_scalar_max(em.get("width"), 0.5), 0.05)
            h = max(_estimate_scalar_max(em.get("height"), w), 0.05)
            size_mult = max(_estimate_scalar_max(em.get("size_over_life"), 1.0), 0.1)
            overdraw_score += mp * min(w * h * size_mult * size_mult, 16.0) * (1.35 if is_add else 1.0)

    emitter_count = len(emitters)
    if total_particles > limits["max_particles"]:
        sev = "ERROR" if total_particles > limits["max_particles"] * 2 else "WARN"
        findings.append(Finding(sev, "PARTICLE_BUDGET_EXCEEDED", source, f"Total max_particles={total_particles} exceeds '{tier}' budget ({limits['max_particles']})."))
    if emitter_count > limits["max_emitters"]:
        findings.append(Finding("WARN", "EMITTER_COUNT_HIGH", source, f"Emitter count={emitter_count} exceeds '{tier}' guideline ({limits['max_emitters']})."))
    if light_count > limits["max_lights"]:
        findings.append(Finding("WARN", "DYNAMIC_LIGHTS_HIGH", source, f"Dynamic lights={light_count} exceeds '{tier}' cap ({limits['max_lights']})."))
    if overdraw_score > limits["max_overdraw"]:
        findings.append(Finding("WARN", "HIGH_OVERDRAW_RISK", source, f"Estimated overdraw score ({overdraw_score:.1f}) exceeds '{tier}' threshold ({limits['max_overdraw']})."))
    if visible_emitters >= 3 and additive_emitters == visible_emitters and contrast_backing_emitters == 0:
        findings.append(
            Finding(
                "WARN",
                "ALL_ADDITIVE_WHITEOUT_RISK",
                source,
                f"All {visible_emitters} particle emitters use Additive blend with 0 dark Alpha/Premultiplied/Modulate contrast backing layers.",
            )
        )

    summary = {
        "source": source,
        "target": target,
        "tier": tier,
        "emitters": emitter_count,
        "additive_emitters": additive_emitters,
        "contrast_backing_emitters": contrast_backing_emitters,
        "total_max_particles": total_particles,
        "dynamic_lights": light_count,
        "overdraw_score": round(overdraw_score, 2),
        "error_count": sum(1 for f in findings if f.severity == "ERROR"),
        "warn_count": sum(1 for f in findings if f.severity == "WARN"),
    }
    return findings, summary


def _collect_targets(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    if path.is_dir():
        return sorted(p for p in path.rglob("*.json") if p.is_file())
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description="Strict LumenFX Effect JSON, GLSL & Budget Validator.")
    parser.add_argument("path", help="Path to a LumenFX effect .json file or directory of effects.")
    parser.add_argument("--target", choices=("all", "26.2", "opengl_direct"), default="all", help="Parser compatibility profile (default: all = strict cross-version safe).")
    parser.add_argument("--tier", choices=sorted(TIERS.keys()), default="combat", help="Concurrency performance tier (default: combat).")
    parser.add_argument("--assets-root", type=Path, default=None, help="Optional path to resource pack 'assets/' directory to verify shaders & textures exist.")
    parser.add_argument("--strict", action="store_true", help="Fail with exit code 1 if any WARN is reported.")
    parser.add_argument("--json", action="store_true", dest="as_json", help="Emit machine-readable JSON report.")
    args = parser.parse_args()

    target_path = Path(args.path)
    files = _collect_targets(target_path)
    if not files:
        print(f"ERROR: No JSON files found at {target_path}", file=sys.stderr)
        return 2

    all_reports = []
    total_errors = 0
    total_warns = 0

    for file_path in files:
        try:
            data = json.loads(file_path.read_text(encoding="utf-8-sig"))
        except Exception as exc:
            findings = [Finding("ERROR", "JSON_SYNTAX_ERROR", file_path.name, str(exc))]
            summary = {"source": str(file_path), "target": args.target, "tier": args.tier, "error_count": 1, "warn_count": 0}
        else:
            findings, summary = validate_effect(data, str(file_path), target=args.target, tier=args.tier, assets_root=args.assets_root)
        total_errors += summary["error_count"]
        total_warns += summary["warn_count"]
        all_reports.append({"summary": summary, "findings": [asdict(f) for f in findings]})

    if args.as_json:
        print(json.dumps({"total_files": len(files), "total_errors": total_errors, "total_warns": total_warns, "reports": all_reports}, ensure_ascii=False, indent=2))
    else:
        for rep in all_reports:
            s = rep["summary"]
            status = "PASS" if s["error_count"] == 0 and (not args.strict or s["warn_count"] == 0) else "FAIL"
            print(f"[{status}] {s['source']} (target={s['target']} | tier={s['tier']} | emitters={s.get('emitters', 0)} | max_particles={s.get('total_max_particles', 0)} | overdraw={s.get('overdraw_score', 0)} | ERROR={s['error_count']} | WARN={s['warn_count']})")
            for f in rep["findings"]:
                print(f"  [{f['severity']:5s}] [{f['code']}] {f['location']} -> {f['message']}")
        print(f"Summary: {len(files)} file(s) checked | ERROR={total_errors} | WARN={total_warns}")

    if total_errors > 0 or (args.strict and total_warns > 0):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
