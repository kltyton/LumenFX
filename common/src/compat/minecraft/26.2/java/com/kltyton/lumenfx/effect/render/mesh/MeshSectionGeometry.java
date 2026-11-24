package com.kltyton.lumenfx.effect.render.mesh;

import java.util.List;

/** Section ranges shared by static assets and the live mesh renderer. */
public interface MeshSectionGeometry {
    List<StaticMeshGeometry.Section> sections();
}
