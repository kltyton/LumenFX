# LumenFX Official Showcase Pack

A ready-to-load Minecraft resource pack containing all **10 showcase VFX effects** featured in the LumenFX README and CurseForge page — including faithful recreations of the signature **Low-Drag-MC/Photon** showcase scenes plus **5 additional next-gen LumenFX effects**.

> This resource pack lives strictly in `examples/showcase-pack/` and is **never bundled** into the lightweight LumenFX runtime mod JARs.

## Included Effects

### Photon-Parity Showcase (`lumenfx_showcase:photon/*`)
| Effect ID | Highlights |
| :--- | :--- |
| `lumenfx_showcase:photon/dynamic_lights_plaza` | Real-time cyan, gold, and purple dynamic point lights, volumetric halos, vertical light pillar, Fresnel orb, and glowing pentagram sigil |
| `lumenfx_showcase:photon/volumetric_temple_shafts` | 3D raymarched volumetric fog volumes, directional spot + point lights, depth-soft volumetric light shafts, and floating dust motes |
| `lumenfx_showcase:photon/kila_material_gallery` | 7 custom GLSL procedural VFX materials (toon fire, HDR ring, lightning arcs, Fresnel shield, polar astral swirl, cellular dissolve core, organic flame) + glowing pentagram sigil |
| `lumenfx_showcase:photon/particle_collision_stairs` | Real-time world block collision with gravity, bounce, and damping across stairs, slabs, and walls |
| `lumenfx_showcase:photon/hologram_fox_swarm` | Custom 3D `.lfmesh` low-poly fox pack rendered with animated horizontal hologram scanlines and HDR bloom |

### Next-Gen Bonus Showcase (`lumenfx_showcase:bonus/*`)
| Effect ID | Highlights |
| :--- | :--- |
| `lumenfx_showcase:bonus/supernova_black_hole` | Pitch-black event horizon, Einstein photon ring, tilted swirling accretion disk, relativistic polar jets, and orbiting star dust |
| `lumenfx_showcase:bonus/arcane_prism_ribbons` | Double-layered rotating golden-cyan arcane mandala with intertwined tri-color helical ribbon trails |
| `lumenfx_showcase:bonus/orbital_plasma_beam` | Sky-to-ground HDR plasma pillar, expanding ground shockwave rings, deferred scorch decal, volumetric mist, and bouncing sparks |
| `lumenfx_showcase:bonus/frost_nova_shatter` | Faceted 3D hexagonal ice crystal spires with glacial Fresnel shading, frost-flower ground seal, cryogenic fog, and snowflakes |
| `lumenfx_showcase:bonus/celestial_sakura_sanctuary` | Drifting cherry blossom petals, HDR golden fireflies, localized multi-color dynamic lighting, and moonlit volumetric mist |

## How to Use

1. Copy or zip `examples/showcase-pack/` into your Minecraft instance's `resourcepacks/` folder and enable it in **Options -> Resource Packs**.
2. Spawn any effect from your mod or test harness using `VfxEffects.spawn(clientLevel, Identifier.parse("lumenfx_showcase:photon/kila_material_gallery"), pos, seed)`.
