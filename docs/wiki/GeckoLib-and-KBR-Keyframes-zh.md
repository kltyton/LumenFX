<p align="right">
  <a href="GeckoLib-and-KBR-Keyframes">English</a> | <strong>简体中文</strong>
</p>

# 动画粒子关键帧集成（GeckoLib、KBR / KBR-Lite、PAL / PALMore、合拍 AnimCue）

LumenFX 在全部 10 个目标上原生内置对以下动画运行库的**纯可选软兼容**（零前置依赖；未安装对应模组时不会加载任何相关类，也不会应用对应 Mixin）：

- **GeckoLib 4.x**（Minecraft 1.20.1 / 1.21.1）与 **GeckoLib 5.x**（Minecraft 26.1.2 / 26.2 / 26.3）
- **KBR / KBR-Lite**（`kltyton_bedrock_runtime`，含完整版与 Lite 版）
- **Player Animation Library（PAL）**（1.21.1 / 26.1.2 / 26.2 / 26.3）与 **PlayerAnimationLibraryMoreRotation（PALMore）**（含 1.20.1 `PalMoreKeyframePlayer` 及 3 轴 bend 变形）
- **合拍（AnimCue，`interactive_animation` 0.1.2+）**（本地客户端场景与服务端同步联机场景，含多参与者同名定位点隔离与场景结束自动清理）

---

## 与各动画库保持一致的触发策略

| 动画运行库 | 是否自动生成粒子 | 联动行为说明 |
| :--- | :---: | :--- |
| **独立 GeckoLib（未装 KBR）** | 否（保留原回调接口） | 与 GeckoLib 原生语义一致，不做自动生成粒子。模组在 `setParticleKeyframeHandler(...)` 回调里调用 `LumenFxKeyframeBridge.trigger(...)` 后，LumenFX 自动接管 **3D 定位点/骨骼姿态跟随** 与 **控制器重置自动清理**。 |
| **KBR / KBR-Lite 共装** | 是（沿用 KBR 自动播放） | 拦截 KBR 的 `GeckoLibHelper.processParticleEffect`（及完整版 `BedrockAnimationParticles.create`）：命中已加载的 LumenFX 特效（或 `lumenfx:` 前缀）时交由 LumenFX 播放并取消 KBR 后续解析，彻底避免 KBR 抛 `Unknown particle id` 或 Molang 语法异常；命中 KBR 粒子则原样放行给 KBR；两者均未注册时安全放行给自定义回调。1.20.1 / 1.21.1 / 26.x 全版本不受 Mixin 加载顺序影响。 |
| **PAL / PALMore** | 否（保留原回调接口） | 与 PAL / PALMore 原生语义一致，不做自动生成粒子。模组在 PAL / PALMore 的 `setParticleKeyframeHandler(...)`（或 `CustomKeyFrameEvents.PARTICLE_KEYFRAME_EVENT`）回调中调用 `LumenFxKeyframeBridge.trigger(...)` 后，LumenFX 自动逐帧采样 **玩家骨骼与 bend 变形后的 3D 世界坐标与旋转**，并在动画停止或重置时自动清理。 |
| **合拍（AnimCue 0.1.2+）** | 是（沿用合拍自动播放） | 严格遵循合拍内置优先级（**原版 `ParticleType` → KBR 粒子 → LumenFX**）：客户端本地场景在 `KbrParticleCompat#spawn` 未命中原版与 KBR 时自动回落至 LumenFX；服务端同步的联机场景在客户端 `ClientScene` 按时间轴自动触发已加载的 LumenFX 特效（即使专用服务器未装 LumenFX 也能正常播放），按参与者实体/角色隔离同名定位点、支持玩家与骨骼/bend 3D 跟随，并在场景结束或取消时自动清理。 |

---

## KBR / KBR-Lite 自动分流规则

在 Blockbench 动画 `.animation.json` 中照常编写标准 `particle_effects` 关键帧：

```json
"particle_effects": {
  "0.25": {
    "effect": "my_mod:fire_slash",
    "locator": "sword_tip",
    "pre_effect_script": "scale=1.25; color=#FFAA33; follow=true; rotate=true"
  }
}
```

| 效果 ID 写法 | 共装 KBR / KBR-Lite 时的路由行为 |
| :--- | :--- |
| `my_mod:fire_slash` | 若已加载同名 LumenFX JSON / Photon `.fx` / Unity / Niagara 特效，由 LumenFX 播放并跳过 KBR；否则若在 KBR `MolangParticleEngine` / `BedrockParticleBindings` 中注册，则原样交给 **KBR 暴雪/3D 粒子**；均未注册时跳过 KBR 避免报错。 |
| `lumenfx:my_mod/fire_slash` *(推荐，单冒号安全格式)* 或 `lumenfx:my_mod:fire_slash` / `lumenfx/my_mod:fire_slash` | 显式强制路由至 LumenFX（即使资源未找到也不会传给 KBR）。 |
| `fire_slash` *(无命名空间)* | 依次查找 `lumenfx:fire_slash` 与 `minecraft:fire_slash`；未命中时按上方规则回落。 |

---

## 3D 定位点、骨骼与玩家 Bend 跟随

- **GeckoLib 5.x（26.1.2 / 26.2 / 26.3）**：自动挂载至 `RenderPassInfo` 的定位点与骨骼位置监听器（`addLocatorPositionListener` + `addBonePositionListener`），并从 `GeoLocator` 姿态栈中提取完整 3D 旋转四元数；同时支持以 `AnimatableManager` 或 `Entity` 作为 `host`。
- **GeckoLib 4.x（1.20.1 / 1.21.1）**：自动为处于绑定状态的 `GeoBone` 开启世界矩阵追踪，同时支持骨骼名与（共装 KBR 时）KBR 附加的 Blockbench `LocatorValue` 子定位点精确 3D 世界坐标与旋转。
- **PAL / PALMore（1.20.1–26.3）**：
  - 支持全部标准玩家骨骼（`body`、`head`、`torso`、`right_arm`/`rightArm`、`left_arm`/`leftArm`、`right_leg`/`rightLeg`、`left_leg`/`leftLeg`、`right_item`/`rightItem`/`right_hand`、`left_item`/`leftItem`/`left_hand`、`cape`、`elytra`）、自定义 `pivotBones`，以及 PAL Bend Player Tools / DragonCore 弯曲辅助定位点（`torso_bend`、`right_arm_bend`/`Right_Arm_Lower`、`left_arm_bend`/`Left_Arm_Lower`、`right_leg_bend`/`Right_Leg_Lower`、`left_leg_bend`/`Left_Leg_Lower`）；
  - 实时结合 PAL 原生 X 轴 `bend` 与 PALMore 的 3 轴 `bend.rotation`（X/Y/Z）、`bend.position`、`bend.scale` 计算世界坐标与旋转四元数。

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
- 动画控制器重置或停止（GeckoLib 5 `reset()`、GeckoLib 4 `resetEventKeyFrames()`、PAL `resetEventKeyFrames()` / `stop()`、PALMore 1.20.1 `PalMoreKeyframePlayer` 播放结束）；
- 触发 `stop=true` 指令；
- 实体移除或切换维度；
- 客户端重载资源包（`F3+T`）或退出世界。

---

## 公开 Java API（`LumenFxKeyframeBridge`）

### 在 PAL / PALMore 回调中触发并自动跟随弯曲骨骼

```java
import com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge;
import net.minecraft.client.multiplayer.ClientLevel;

// 示例：在 PalMoreAnimationController（26.x / 1.21.1）的粒子关键帧回调中触发
controller.setParticleKeyframeHandler(ctx -> {
    if (ctx.avatar().level() instanceof ClientLevel clientLevel) {
        LumenFxKeyframeBridge.trigger(
                clientLevel,
                ctx.avatar(),               // host：玩家实体
                ctx.avatar(),               // entity
                ctx.animationController(),  // controllerKey：用于动画停止/重置时自动清理
                "pal",
                ctx.effect(),
                ctx.locator(),              // 支持 right_item、right_arm_bend 等标准与弯曲骨骼名
                ctx.script(),
                null,
                ctx.avatar().getX(), ctx.avatar().getY(), ctx.avatar().getZ()
        );
    }
});
```

### 过滤、监听与手动控制

```java
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

// 3. 可选：手动停止实体上全部关键帧绑定特效
LumenFxKeyframeBridge.stopForHost(entity);
```
