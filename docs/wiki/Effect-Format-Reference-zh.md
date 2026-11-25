<p align="right">
  <a href="Effect-Format-Reference">English</a> | <strong>简体中文</strong>
</p>

# 效果格式与自定义着色器参考

LumenFX 直接从标准 Minecraft 资源包目录加载粒子效果、纹理、向量场与自定义 GLSL 着色器。

## 资源路径约定

以效果 ID `my_mod:magic/fireball` 为例，对应资源路径如下：

- **LumenFX JSON（Unity / Niagara / 手写效果）**：
  `assets/my_mod/lumenfx/effects/magic/fireball.json`
- **Photon `.fx` 效果**：
  `assets/my_mod/lumenfx/effects/magic/fireball.fx`
- **自定义 GLSL 片元着色器（`my_mod:vfx/plasma`）**：
  `assets/my_mod/shaders/vfx/plasma.fsh`
- **3D 向量场（`my_mod:curl_32`）**：
  `assets/my_mod/lumenfx/vector_fields/curl_32.lfvf`
- **纹理贴图**：标准 Minecraft 纹理路径，例如 `my_mod:textures/vfx/spark.png`

---

## JSON 结构示例（`format_version: 1`）

```json
{
  "format_version": 1,
  "materials": {
    "glow": {
      "fragment_shader": "lumenfx:core/effect",
      "textures": {
        "Sampler0": "my_mod:textures/vfx/spark.png"
      },
      "blend": "additive",
      "depth_test": true,
      "depth_write": false,
      "cull": false,
      "parameters": [1.0, 0.0, 0.0, 0.0]
    }
  },
  "emitters": [
    {
      "name": "embers",
      "renderer": "sprite",
      "material": "glow",
      "local_space": false,
      "delay": 0.0,
      "duration": 3.0,
      "loop": true,
      "max_particles": 512,
      "rate": 40.0,
      "lifetime": { "min": 0.6, "max": 1.2 },
      "width": 0.25,
      "height": 0.25,
      "start_speed": { "min": 1.5, "max": 3.0 },
      "shape": { "type": "sphere", "size": [0.3, 0.3, 0.3], "surface": false },
      "force_over_life": [0.0, -4.5, 0.0],
      "size_over_life": {
        "keys": [
          { "time": 0.0, "value": 0.4, "interpolation": "linear" },
          { "time": 0.3, "value": 1.0, "interpolation": "linear" },
          { "time": 1.0, "value": 0.0, "interpolation": "linear" }
        ]
      },
      "color": [
        1.0,
        { "keys": [{ "time": 0.0, "value": 0.85 }, { "time": 1.0, "value": 0.2 }] },
        0.1,
        { "keys": [{ "time": 0.0, "value": 1.0 }, { "time": 1.0, "value": 0.0 }] }
      ]
    }
  ]
}
```

---

## 发射器核心字段表

| 字段 | 含义 |
| :--- | :--- |
| `renderer` | 渲染器类型：`sprite`、`ribbon`、`beam` 或 `mesh` |
| `material` | 引用当前效果 `materials` 对象中的材质键名 |
| `local_space` | 粒子是否在发射器局部空间模拟（`true`）或世界空间模拟（`false`） |
| `delay` / `duration` / `loop` | 起始延迟（秒）、单轮持续时间（秒）及是否循环 |
| `rate` / `distance_spacing` / `bursts` | 按时间发射速率（粒/秒）、按世界移动距离间隔（格）及定时爆发数组 |
| `lifetime` / `width` / `height` / `rotation` | 粒子出生时按归一化发射器年龄（`0..1`）求值；旋转单位为弧度 |
| `start_speed` / `start_direction` / `start_color` | 初始速度（格/秒）、初始方向向量与出生 RGBA 颜色乘数 |
| `velocity_min` / `velocity_max` / `acceleration` | 兼容旧格式的初始速度区间（格/秒）与恒定加速度（格/秒²） |
| `velocity_over_life` / `force_over_life` | 按归一化粒子年龄（`0..1`）求值的三轴附加速度曲线与外力曲线 |
| `angular_velocity` | 按粒子年龄积分的角速度曲线（弧度/秒） |
| `drag` / `mass` / `integrator` | 空气阻力系数或曲线、出生质量及积分器模式（`analytic` 或 `niagara`） |
| `shape` | 发射形状：`point`、`box`、`sphere`、`cylinder`、`cone`（`size` 为各轴半尺寸，锥体 Y 为高度） |
| `size_over_life` / `color` / `custom_data` | 按归一化粒子年龄求值的尺寸倍率、RGBA 颜色与 4 个自定义着色器浮点参数 |
| `atlas_columns` / `atlas_rows` / `atlas_frame` / `atlas_row` | 序列帧图集行列数、帧曲线与可选固定行索引 |
| `uv_mode` / `uv_distance` | Ribbon 丝带 UV 映射模式（`stretch` 全长拉伸或按世界距离重复） |
| `subdivisions` / `width_segments` / `attach_head` | Ribbon 纵向细分、横向细分以及停止发射后头部保持附着开关 |
| `sort_order` / `sort_mode` | 渲染器优先级与精灵粒子排序方式（`none` / `distance` / `view_depth`） |
| `movement_threshold` | 超过允许单步位移距离时自动断开并开启新轨迹段，避免拉丝 |

---

## 标量曲线与复合表达式

任意支持曲线的标量字段均可写为以下形式：

- **常量数值**：`1.5`
- **随机区间（同一粒子生命周期内保持稳定）**：`{ "min": 0.5, "max": 1.5 }`
- **分段关键帧曲线**：`{ "keys": [ { "time": 0.0, "value": 1.0, "arrive": 0.0, "leave": 0.0, "interpolation": "cubic" } ] }`
  - `interpolation` 支持 `constant`、`linear`、`cubic`（Hermite 切线单位为“值 / 曲线时间”）
- **复合运算**：`{ "multiply": [ ... ] }`（最多 5 项曲线因子）或 `{ "add": [ ... ] }`

---

## 材质与自定义 GLSL 片元着色器

内置片元着色器：
- `lumenfx:core/effect` — 采样 `Sampler0` 并与粒子 RGBA 颜色相乘。
- `lumenfx:core/color` — 仅使用粒子 RGBA 颜色输出，不采样贴图。

### 编写自定义片元着色器

将 `.fsh` 文件放置于 `assets/<namespace>/shaders/vfx/<name>.fsh`，并在材质中声明 `"fragment_shader": "<namespace>:vfx/<name>"`。

可用的顶点传参输入：
- `in vec2 effectUV;` — 主 UV 坐标
- `in vec2 effectUV1;` — 次 UV 坐标（用于双 UV 丝带 / Niagara 材质）
- `in vec4 effectColor;` — 当前粒子 RGBA 颜色
- `in vec4 effectParticle;` — `(归一化年龄, 0..1 随机种子, 总寿命秒数, 出生时刻秒数)`
- `in vec4 effectCustom;` — 来自 `custom_data` 的 4 个逐粒子自定义浮点值
- `in vec3 effectPosition;` — 粒子位置

`EffectFrame` Uniform 块：
- `vec4 EffectClock;`（`EffectClock.x` 为世界累计秒数）
- `vec4 MaterialParameters;`（来自材质配置中的 4 项 `parameters`）

材质状态配置：
- `blend`：`alpha`、`additive`、`add`、`premultiplied`、`multiply`、`opaque`
- `depth_test` / `depth_compare` / `depth_write`：深度测试、比较函数与深度写入控制（26.2 自动适配原版反向 Z）
- `cull`：背面剔除开关
- `fog`：`{ "enabled": false, "per_pixel": false }`
