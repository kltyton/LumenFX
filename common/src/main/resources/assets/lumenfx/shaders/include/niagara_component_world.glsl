#ifndef LFX_NIAGARA_COMPONENT_WORLD
#define LFX_NIAGARA_COMPONENT_WORLD

struct LfxComponentWorldPosition {
    vec3 Tile;
    vec3 Offset;
};

LfxComponentWorldPosition lfx_component_world_position(vec3 position, bool localSpace, bool previous) {
    if (!localSpace) return LfxComponentWorldPosition(SimulationTile.xyz, position);
    vec3 offset;
    if (previous) offset = (position.x * PreviousOwnerRelative0.xyz + position.y * PreviousOwnerRelative1.xyz
            + position.z * PreviousOwnerRelative2.xyz) + PreviousOwnerRelative3.xyz;
    else offset = (position.x * OwnerRelative0.xyz + position.y * OwnerRelative1.xyz
            + position.z * OwnerRelative2.xyz) + OwnerRelative3.xyz;
    return LfxComponentWorldPosition(OwnerPositionHigh.xyz * 0.000000476837158203125, offset);
}

vec3 lfx_component_translated_position(LfxComponentWorldPosition position) {
    precise vec3 tile = position.Tile + ComponentPreViewTile.xyz;
    precise vec3 offset = position.Offset + ComponentPreViewOffset.xyz;
    precise vec3 tileOffset = tile * 2097152.0;
    precise vec3 translated = tileOffset + offset;
    return translated;
}

#endif
