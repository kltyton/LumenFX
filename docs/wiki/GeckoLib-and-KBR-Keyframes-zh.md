<p align="right">
  <a href="GeckoLib-and-KBR-Keyframes">English</a> | <strong>简体中文</strong>
</p>

# GeckoLib 与 KBR 粒子关键帧集成

LumenFX 在全部 8 个目标上原生内置对 **GeckoLib 4.x**（Minecraft 1.20.1 / 1.21.1）、**GeckoLib 5.x**（Minecraft 26.1.2 / 26.2）以及 **KBR（`kltyton_bedrock_runtime`）** 粒子关键帧系统的纯可选软兼容。

未安装 GeckoLib 或 KBR 时，LumenFX 不会加载任何对应类，也不会应用关键帧 Mixin，零额外依赖与开销。

---

## 自动分流机制（与 KBR 原生粒子和平共存）

在 Blockbench 动画 `.animation.json` 中照常编写标准 `particle_effects` 关键帧即可：

```json
"particle_effects": {
  "0.25": {
    "effect": "my_mod:fire_slash",
    "locator": "sword_tip",
    "pre_effect_script": "scale=1.25; color=#FFAA33; follow=true; rotate=true"
  }
}
```

| 效果 ID 写法 | 路由行为 |
| :--- | :--- |
| `my_mod:fire_slash` | 若 `my_mod:fire_slash` 是已加载的 LumenFX JSON / Photon `.fx` / Unity / Niagara 特效，LumenFX 自动接管播放；否则原样放行给 **KBR 暴雪/3D 粒子** 或你的自定义 `AnimationController` 回调。 |
| `lumenfx:my_mod/fire_slash` *(推荐，单冒号安全格式)* 或 `lumenfx:my_mod:fire_slash` / `lumenfx/my_mod:fire_slash` | 显式强制路由至 LumenFX（即使资源未找到也不回落给 KBR）。与 KBR 共装时推荐使用单冒号格式 `lumenfx:my_mod/fire_slash`，以兼容严格的 `Identifier.parse(...)`。 |
| `fire_slash` *(无命名空间)* | 依次查找 `lumenfx:fire_slash` 与 `minecraft:fire_slash`；未命中时原样放行。 |

---

## 3D 定位点与骨骼跟随

- **GeckoLib 5.x（26.1.2 / 26.2）**：自动挂载至 `RenderPassInfo` 的定位点与骨骼位置监听器（`addLocatorPositionListener` + `addBonePositionListener`），并从 `GeoLocator` 姿态栈中提取完整 3D 旋转四元数。
- **GeckoLib 4.x（1.20.1 / 1.21.1）**：自动为处于绑定状态的 `GeoBone` 开启世界矩阵追踪，同时支持直接以骨骼名作为定位点，以及在共装 KBR 时读取 KBR 附加的 Blockbench `LocatorValue` 子定位点精确 3D 世界坐标与旋转。
- **KBR 原生基岩动画**：在 `BedrockAnimationParticles.create(...)` 未命中 KBR `MolangParticleEngine` 时自动回落到 LumenFX 播放。

---

## 支持的 `pre_effect_script` 参数指令

多个指令之间可用 `;` 或换行分隔，自动兼容并剥离可选 Molang 前缀（如 `v.`、`variable.`、`lumenfx_`、`lumenfx.`，例如 `v.lumenfx_scale=1.5`）：

| 指令名 | 示例值 | 说明 |
| :--- | :--- | :--- |
| `follow` / `bind_to_actor` / `attach` | `true` \| `false` | 每帧跟随定位点或实体（默认继承 `bind_to_actor`，指定 `locator` 时默认为 `true`）。 |
| `rotate` / `inherit_rotation` | `true` \| `false` | 继承定位点 / 骨骼的 3D 旋转四元数（默认 `true`）。 |
| `scale` / `size` | `1.5` | 统一缩放倍率，与实体缩放相乘（默认 `1.0`）。 |
| `color` / `tint` | `#FF8040`、`#FF8040CC`、`1,0.5,0.25,1` | 实例级 RGBA 染色乘数。 |
| `offset` / `pos` | `0,0.5,-0.25` | 附加偏移量（单位：方块）。 |
| `beam_end` / `target_locator` | `hand_left` | 指定第二个定位点实时驱动 `EffectHandle.setBeamEnd(...)`。 |
| `stop` / `close` / `end` | `true` | 平滑停止并排空当前实体/控制器/定位点上匹配的已激活特效。 |
| `restart` / `respawn` | `true` | 生成前先关闭同一槽位上的旧实例。 |
| `seed` | `4242` | 覆盖确定性随机种子。 |

---

## 生命周期自动清理

绑定到关键帧的特效在以下时机自动平滑关闭并释放：
- 动画控制器重置或切换动画（GeckoLib 5 的 `reset()` / GeckoLib 4 的 `resetEventKeyFrames()`）；
- 触发 `stop=true` 指令；
- 实体移除或切换维度；
- 客户端重载资源包（`F3+T`）或退出世界。

---

## 公开 Java API（`LumenFxKeyframeBridge`）

模组开发者可通过 `com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge` 进行过滤、重定向、监听或手动控制：

```java
import com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge;

// 1. 可选：按条件过滤或改写关键帧请求
LumenFxKeyframeBridge.addFilter(request -> {
    if (request.entity() != null && request.entity().isInvisible()) {
        return false; // 跳过生成
    }
    return true;
});

// 2. 可选：监听特效生成事件（例如设置 Niagara 自定义参数）
LumenFxKeyframeBridge.addSpawnListener((request, rawHandle) -> {
    // 可将 rawHandle 转为 com.kltyton.lumenfx.api.client.EffectHandle
});

// 3. 可选：查询某特效是否已在 LumenFX 中加载
boolean loaded = VfxEffects.hasEffect(effectId);

// 4. 可选：手动停止实体上全部关键帧绑定特效
LumenFxKeyframeBridge.stopForHost(entity);
```
