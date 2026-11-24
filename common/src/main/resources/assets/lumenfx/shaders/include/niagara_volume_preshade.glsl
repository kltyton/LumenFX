void main() {
    ivec3 voxel = ivec3(gl_GlobalInvocationID.xyz);
    ivec3 dimensions = imageSize(VolumeBakeExtinction);
    if (any(greaterThanEqual(voxel, dimensions))) return;
    vec3 jitter = vec3(0.5);
    if (VolumePass.z > 0.5) {
        ivec2 pixel = ivec2(voxel.y * dimensions.x + voxel.x, voxel.z) & 127;
        pixel.y += (int(VolumeResolution.w) & 63) * 128;
        jitter = vec3(texelFetch(VolumeBakeNoiseSampler, pixel, 0).rg, texelFetch(VolumeBlueNoiseSampler, pixel, 0).r);
    }
    vec3 uvw = (vec3(voxel) + jitter) / vec3(dimensions);
    vec3 local = mix(VolumeBoundsMin.xyz, VolumeBoundsMax.xyz, uvw);
    vec3 world = (VolumeLocalToWorld * vec4(local, 1.0)).xyz;
    LfxVolumeMaterial material = lfx_evaluate_volume(local, world);
    imageStore(VolumeBakeExtinction, voxel, vec4(material.extinction, 0.0));
    imageStore(VolumeBakeEmission, voxel, vec4(material.emission, 0.0));
    imageStore(VolumeBakeAlbedo, voxel, vec4(material.albedo, 0.0));
}
