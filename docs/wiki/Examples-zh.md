<p align="right">
  <a href="Examples">English</a> | <strong>简体中文</strong>
</p>

# 示例特效与开发者参考资源包

LumenFX 在仓库的 **[`examples/showcase-pack/`](https://github.com/kltyton/LumenFX/tree/main/examples/showcase-pack)** 目录提供了开箱即用的官方示例资源包，供模组与资源包开发者直接运行、拆解与复用。

> **库本体与产物严格隔离：** 所有示例特效、`.lfmesh` 三维模型、程序化贴图与自定义 GLSL 片元着色器均仅存放在仓库的 `examples/` 目录与本 Wiki 中，**绝不写入** `common/`、`targets/` 库源码本体，也**绝不打入**最终发布的任何 LumenFX `.jar` 包中。

---

## 示例资源包目录结构

```text
examples/showcase-pack/
├── pack.mcmeta
├── README.md
└── assets/lumenfx_showcase/
    ├── lumenfx/
    │   ├── effects/
    │   │   ├── classic/          # 5 组单特性入门模板特效（结构精简，便于直接复制改写）
    │   │   ├── photon/           # 5 组 Photon 对标实机场景（动态光源/体积雾/材质/碰撞/3D全息）
    │   │   └── bonus/            # 5 组高阶多层复合特效（黑洞/奥术丝带/轨道炮/冰霜新星/樱花圣域）
    │   └── meshes/               # 6 组自定义 3D .lfmesh 模型（地阵面片、倾斜圆盘、光柱、光扇、狐狸、冰晶）
    ├── shaders/vfx/              # 16 组 GLSL 330 Core 自定义片元着色器（.fsh）
    └── textures/vfx/             # 6 组程序化 PNG 贴图（柔光、星芒、五芒星阵、冰霜阵、焦痕、樱瓣）
```

---

## 完整示例清单（共 15 组）

### 1. 单特性入门模板（`lumenfx_showcase:classic/*`）
每个文件仅聚焦 1–2 个核心特性，结构短小清晰，适合模组作者直接拷贝到自己的项目中改造：

| 特效 ID | 示范核心特性 | 源文件 |
| :--- | :--- | :--- |
| `lumenfx_showcase:classic/rune_ribbon_stream` | 地面网格法阵 + `ribbon` 拖尾渲染器、`orbital_velocity` 环绕与颜色渐变 `stops` | [`rune_ribbon_stream.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/rune_ribbon_stream.json) |
| `lumenfx_showcase:classic/astral_vortex_portal` | 极坐标星漩 GLSL 着色器 + `volumes` 体积雾 + 向心 `radial_velocity` 吸引粒子 | [`astral_vortex_portal.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/astral_vortex_portal.json) |
| `lumenfx_showcase:classic/blazing_ember_fireball` | 原生 HDR 泛光（`hdr` / `emissive_scale` / `bloom_intensity`）、火焰着色器与旋度噪声余烬 | [`blazing_ember_fireball.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/blazing_ember_fireball.json) |
| `lumenfx_showcase:classic/emerald_plasma_comet` | 细胞溶解核心着色器 + 噪声扰动翡翠等离子 `ribbon` 拖尾 | [`emerald_plasma_comet.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/emerald_plasma_comet.json) |
| `lumenfx_showcase:classic/luminous_physics_fountain` | 圆锥发射器 + `gravity` 重力、`drag` 阻力与真实世界方块碰撞 `collision`（反弹/阻尼/寿命衰减） | [`luminous_physics_fountain.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/classic/luminous_physics_fountain.json) |

### 2. Photon 经典对标场景（`lumenfx_showcase:photon/*`）
完整覆盖动态光照、体积雾与丁达尔光柱、多材质 GLSL 展廊、阶梯物理碰撞与 3D 全息网格群：

| 特效 ID | 技术亮点 | 源文件 |
| :--- | :--- | :--- |
| `lumenfx_showcase:photon/dynamic_lights_plaza` | 多色动态点光源（`lights`）、体积光晕（`volumes`）、菲涅尔球、竖直光柱与旋转五芒星法阵 | [`dynamic_lights_plaza.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/dynamic_lights_plaza.json) |
| `lumenfx_showcase:photon/volumetric_temple_shafts` | 3D 体积雾（`volumes`）、定向聚光灯 + 点光源、深度软粒子（`soft_particles`）光束与浮尘 | [`volumetric_temple_shafts.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/volumetric_temple_shafts.json) |
| `lumenfx_showcase:photon/kila_material_gallery` | 7 种程序化 GLSL 材质（卡通火焰、HDR 光环、闪电电弧、菲涅尔护盾、星辰漩涡、溶解核心、有机火焰）+ 地面五芒星 | [`kila_material_gallery.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/kila_material_gallery.json) |
| `lumenfx_showcase:photon/particle_collision_stairs` | 真实世界楼梯、半砖与墙面物理碰撞，带重力、弹性反弹与速度阻尼 | [`particle_collision_stairs.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/particle_collision_stairs.json) |
| `lumenfx_showcase:photon/hologram_fox_swarm` | 自定义 3D `.lfmesh` 低模狐狸群配合动态扫描线着色器（`hologram_scanline.fsh`）与地面光照 | [`hologram_fox_swarm.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/photon/hologram_fox_swarm.json) |

### 3. 次世代多层复合特效（`lumenfx_showcase:bonus/*`）
展示在大型技能、Boss 演出与环境氛围中如何组合多种渲染器、着色器、地面贴花、体积雾与光照：

| 特效 ID | 技术亮点 | 源文件 |
| :--- | :--- | :--- |
| `lumenfx_showcase:bonus/supernova_black_hole` | 纯黑事件视界（`blend: "alpha"`）、爱因斯坦光子环、倾斜吸积盘网格、相对论两极喷流与环绕星尘 | [`supernova_black_hole.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/supernova_black_hole.json) |
| `lumenfx_showcase:bonus/arcane_prism_ribbons` | 双层反向旋转金青色六芒星奥术法阵 + 三色螺旋交织丝带拖尾 | [`arcane_prism_ribbons.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/arcane_prism_ribbons.json) |
| `lumenfx_showcase:bonus/orbital_plasma_beam` | 贯天 HDR 等离子光柱、上升蓄能环、地面扩散冲击波、延迟焦痕贴花（`decals`）、冲击雾与弹跳火星 | [`orbital_plasma_beam.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/orbital_plasma_beam.json) |
| `lumenfx_showcase:bonus/frost_nova_shatter` | 3D 六棱冰晶模型（`ice_crystal.lfmesh`）+ 冰晶菲涅尔着色器、冰花法阵、低温体积雾与飘雪 | [`frost_nova_shatter.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/frost_nova_shatter.json) |
| `lumenfx_showcase:bonus/celestial_sakura_sanctuary` | 旋度噪声飘落樱花瓣、HDR 金色萤火虫、多色局部动态光源与月下樱雾 | [`celestial_sakura_sanctuary.json`](https://github.com/kltyton/LumenFX/blob/main/examples/showcase-pack/assets/lumenfx_showcase/lumenfx/effects/bonus/celestial_sakura_sanctuary.json) |

---

## 开发者快速上手

### 1. 在 Java 模组中加载与播放

将需要的 JSON、GLSL 着色器、贴图与 `.lfmesh` 复制到你模组的 `src/main/resources/assets/<你的mod_id>/` 下（或将整个 `examples/showcase-pack/` 放入 `.minecraft/resourcepacks/` 并启用），然后在客户端调用：

```java
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxEffects;
import net.minecraft.resources.ResourceLocation; // 26.x 使用 Identifier
import net.minecraft.world.phys.Vec3;

ResourceLocation effectId = ResourceLocation.parse("lumenfx_showcase:bonus/supernova_black_hole");
if (VfxEffects.hasEffect(effectId)) {
    EffectHandle handle = VfxEffects.spawn(clientLevel, effectId, new Vec3(x, y, z), 2026L);
    // 可选：每 tick 更新位置/缩放，或调用 handle.close() 提前终止
}
```

### 2. 在 GeckoLib / KBR 动画关键帧中触发

在 Blockbench 的 `particle_effects` 时间轴轨道填写：
- **Effect（特效）：** `lumenfx_showcase:bonus/frost_nova_shatter`
- **Locator（定位点）：** `cast_origin`（可选 3D 骨骼定位点）
- **Pre-Effect Script（前置脚本）：** `scale=1.2; offset=0,0.1,0`

详细桥接参数见 **[[GeckoLib 与 KBR 粒子关键帧|GeckoLib-and-KBR-Keyframes-zh]]**。

### 3. 编写高质量 LumenFX 特效的实用建议

- **静态常驻元素（法阵、核心球、光柱）：** 务必使用单次爆发（`"rate": 0.0` + `"bursts": [{"time": 0.0, "min": 1, "max": 1, "cycles": 1}]` + `"max_particles": 1`），不要用持续发射率堆叠，否则叠加混合会导致过曝发白。
- **生命周期颜色渐变：** 使用显式 `stops` 结构：`{"stops": [{"time": 0.0, "color": [r, g, b, a]}, ...]}`，避免在四元数组内混写标量与曲线对象。
- **HDR 与泛光控制：** 叠加层建议从 `"emissive_scale": 1.1`–`1.25`、`"bloom_intensity": 0.35`–`0.55` 起调；需要清晰轮廓的实体核心（如黑洞视界、卡通火焰、半透明冰晶）使用 `"blend": "alpha"`。
- **自定义 GLSL 片元着色器：** 将 `.fsh` 放入 `assets/<namespace>/shaders/vfx/<name>.fsh`，材质中填写 `"fragment_shader": "<namespace>:vfx/<name>"`；LumenFX 会自动绑定 `vUv`、`vColor`、`uTime` 与 `Sampler0`。
