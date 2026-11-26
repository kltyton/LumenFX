<p align="right">
  <a href="Getting-Started">English</a> | <strong>简体中文</strong>
</p>

# 快速入门与 API 指南

## 安装方式（玩家与整合包）

1. 下载与你当前 Minecraft 版本及模组加载器（NeoForge、Fabric 或 Forge）对应的单一统一 LumenFX JAR 文件。
2. 将 JAR 文件放入 `.minecraft/mods` 目录。
3. 正常启动游戏即可——LumenFX **无需光影模组**，且**无任何强制外部前置模组**（`LDLib2`、`KilaGraph`、`Iris`、`Sodium`、`GeckoLib` 与 `KBR` 均为纯可选兼容）。

> 在 **Minecraft 26.2** 上，没有“Vulkan 版”与“OpenGL 版”之分。单一统一 JAR 会在环境支持时自动使用 Vulkan，在需要时自动回退至 OpenGL。

---

## 支持目标与 Maven 坐标

所有官方发行包均托管于 **Sighs Maven**（`https://maven.sighs.cc/repository/maven-public/`），Group ID 为 `com.kltyton.lumenfx`。

| Minecraft 版本 | 加载器 | Artifact ID | 版本 | Java 版本 | 图形后端策略 |
| :---: | :---: | :--- | :---: | :---: | :--- |
| **26.2** | NeoForge | `lumenfx-neoforge-26.2` | `0.2.0` | Java 25 | 动态 Vulkan 优先 / OpenGL 自动回退 |
| **26.2** | Fabric | `lumenfx-fabric-26.2` | `0.2.0` | Java 25 | 动态 Vulkan 优先 / OpenGL 自动回退 |
| **26.1.2** | NeoForge | `lumenfx-neoforge-26.1.2` | `0.2.0` | Java 25 | OpenGL |
| **26.1.2** | Fabric | `lumenfx-fabric-26.1.2` | `0.2.0` | Java 25 | OpenGL |
| **1.21.1** | NeoForge | `lumenfx-neoforge-1.21.1` | `0.2.0` | Java 21 | OpenGL |
| **1.21.1** | Fabric | `lumenfx-fabric-1.21.1` | `0.2.0` | Java 21 | OpenGL |
| **1.20.1** | Forge | `lumenfx-forge-1.20.1` | `0.2.0` | Java 17 | OpenGL |
| **1.20.1** | Fabric | `lumenfx-fabric-1.20.1` | `0.2.0` | Java 17 | OpenGL |

---

## Gradle 接入配置（模组开发者）

### NeoForge / Forge（`build.gradle`）

```groovy
repositories {
    maven {
        name = 'Sighs Maven'
        url = uri('https://maven.sighs.cc/repository/maven-public/')
    }
}

dependencies {
    implementation 'com.kltyton.lumenfx:lumenfx-neoforge-26.2:0.2.0'
    // 可选：通过 jarJar 将 LumenFX 内嵌进你的模组
    // jarJar('com.kltyton.lumenfx:lumenfx-neoforge-26.2:[0.2.0,)')
}
```

### Fabric（`build.gradle`）

```groovy
repositories {
    maven {
        name = 'Sighs Maven'
        url = uri('https://maven.sighs.cc/repository/maven-public/')
    }
}

dependencies {
    modImplementation 'com.kltyton.lumenfx:lumenfx-fabric-26.2:0.2.0'
}
```

---

## Java 客户端 API 快速上手

所有受支持的特效格式（LumenFX JSON、Unity 导出资源、UE Niagara 编译资源与 Photon `.fx`）共用同一客户端入口：`com.kltyton.lumenfx.api.client.VfxEffects`。

### 生成特效实例

```java
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier; // 1.20.1 / 1.21.1 使用 ResourceLocation
import net.minecraft.world.phys.Vec3;

if (VfxEffects.hasEffect(Identifier.parse("my_mod:magic/rune_stream"))) {
    EffectHandle handle = VfxEffects.spawn(
            clientLevel,
            Identifier.parse("my_mod:magic/rune_stream"),
            new Vec3(x, y, z),
            seed
    );
}
```

### 更新位置、姿态、颜色与光束终点

```java
// 更新世界坐标位置
handle.moveTo(new Vec3(nextX, nextY, nextZ));

// 更新完整姿态：世界位置、归一化旋转四元数与整体缩放
handle.setPose(new EffectHandle.Pose(position, rotationQuat, 1.0f));

// 设置实例级 RGBA 颜色乘数
handle.setColor(1.0f, 0.85f, 0.4f, 1.0f);

// 设置光束（BEAM）渲染器的世界终点坐标
handle.setBeamEnd(targetWorldPos);

// 面向需要宿主速度的 Niagara 特效，在同一步发布姿态、宿主位置与速度
handle.setPoseAndActorPosition(pose, actorPos, velocity);
```

### 渲染器附着点跟踪

如果特效位置由实体渲染器或方块实体渲染器在渲染阶段更新（而非每个客户端 tick 更新）：

```java
handle.trackRenderSamples(); // 在 spawn 后调用一次即可
```

启用后，当附着目标离开视野或停止提交渲染样本时，新粒子发射会自动暂停，而已经出生的粒子会继续自然播放至寿命结束。

### 生命周期与自动清理

- **`handle.isAlive()`**：特效自然结束、手动关闭并排空，或因切换维度 / 资源重载（`F3+T`）而失效时返回 `false`。
- **`handle.close()`**：立即停止新粒子发射，允许已有粒子与拖尾平滑播放完毕后再回收资源。
- **切换世界与资源重载**：切换客户端世界时自动清理旧实例；按 `F3+T` 重载资源包时原子发布全新不可变定义并安全释放旧管线。
