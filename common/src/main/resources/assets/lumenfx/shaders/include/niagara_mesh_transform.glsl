#ifndef LFX_NIAGARA_MESH_TRANSFORM
#define LFX_NIAGARA_MESH_TRANSFORM
#moj_import <lumenfx:niagara_component_transform.glsl>

// All positions use source axes and centimetres. World translations are camera-relative.
struct LfxMeshTransformInput {
    vec3 position;
    vec4 orientation;
    vec3 scale;
    vec3 velocity;
    float cameraOffset;
    vec3 meshScale;
    vec4 meshRotation;
    vec3 meshOffset;
    bool meshOffsetWorld;
    bool localSpace;
    int facingMode;
    bool lockedAxisEnabled;
    vec3 lockedAxis;
    int lockedAxisSpace;
    vec3 cameraForward;
    vec3 cameraUp;
    vec3 worldOffset;
    mat3 primitiveLinear;
    mat3 primitiveRotation;
    vec3 primitivePosition;
    vec3 primitiveScale;
};

struct LfxMeshSrt {
    vec3 scale;
    mat3 rotation;
    vec3 translation;
};

struct LfxMeshTransform {
    mat3 linear;
    mat3 rotation;
    vec3 position;
    vec3 inverseScale;
    float determinantSign;
};

vec4 lfx_mesh_quaternion_multiply(vec4 first, vec4 second) {
    return vec4(second.xyz * first.w + first.xyz * second.w + cross(first.xyz, second.xyz),
                first.w * second.w - dot(first.xyz, second.xyz));
}

mat3 lfx_mesh_quaternion_matrix(vec4 q) {
    return lfx_component_rotation(q);
}

vec3 lfx_mesh_world_position(vec3 position, LfxMeshTransformInput inputData) {
    return inputData.localSpace
            ? inputData.primitiveLinear * position + inputData.primitivePosition
            : position + inputData.worldOffset;
}

mat3 lfx_mesh_inverse_linear(mat3 linear, vec3 inverseScale) {
    vec3 squareScale = inverseScale * inverseScale;
    return transpose(mat3(linear[0] * squareScale.x, linear[1] * squareScale.y, linear[2] * squareScale.z));
}

vec3 lfx_mesh_world_to_sim_direction(vec3 direction, LfxMeshTransformInput inputData) {
    return inputData.localSpace ? transpose(inputData.primitiveRotation) * direction : direction;
}

vec3 lfx_mesh_sim_to_world_direction(vec3 direction, LfxMeshTransformInput inputData) {
    return inputData.localSpace ? inputData.primitiveRotation * direction : direction;
}

mat3 lfx_mesh_facing(LfxMeshTransformInput inputData) {
    vec3 facing;
    if (inputData.facingMode == 1) {
        float speed = length(inputData.velocity);
        facing = speed > 0.0 ? inputData.velocity / speed
                : lfx_mesh_world_to_sim_direction(vec3(0.0, 0.0, 1.0), inputData);
    } else if (inputData.facingMode == 2) {
        facing = lfx_mesh_world_to_sim_direction(normalize(-lfx_mesh_world_position(inputData.position, inputData)), inputData);
    } else {
        facing = lfx_mesh_world_to_sim_direction(-inputData.cameraForward, inputData);
    }
    vec3 xAxis, yAxis, zAxis;
    if (inputData.lockedAxisEnabled) {
        vec3 locked = inputData.lockedAxis;
        bool worldAxis = inputData.lockedAxisSpace == 1 || inputData.lockedAxisSpace == 0 && !inputData.localSpace;
        if (worldAxis && inputData.localSpace) locked = transpose(inputData.primitiveRotation) * locked;
        else if (!worldAxis && !inputData.localSpace) locked = inputData.primitiveRotation * locked;
        if (abs(dot(facing, locked)) > 0.99)
            facing = abs(locked.z) > 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);
        zAxis = locked;
        yAxis = normalize(cross(zAxis, facing));
        xAxis = cross(yAxis, zAxis);
    } else {
        vec3 reference;
        if (inputData.facingMode == 3) reference = inputData.cameraUp;
        else {
            float vertical = lfx_mesh_sim_to_world_direction(facing, inputData).z;
            reference = abs(vertical) > 0.99 ? vec3(-sign(vertical), 0.0, 0.0) : vec3(0.0, 0.0, 1.0);
        }
        reference = lfx_mesh_world_to_sim_direction(reference, inputData);
        xAxis = facing;
        yAxis = normalize(cross(reference, facing));
        zAxis = cross(xAxis, yAxis);
    }
    return mat3(xAxis, yAxis, zAxis);
}

LfxMeshSrt lfx_mesh_srt(LfxMeshTransformInput inputData) {
    LfxMeshSrt result;
    result.scale = inputData.scale * inputData.meshScale;
    result.rotation = lfx_mesh_quaternion_matrix(lfx_mesh_quaternion_multiply(
            normalize(inputData.orientation), inputData.meshRotation));
    if (inputData.facingMode != 0) result.rotation = lfx_mesh_facing(inputData) * result.rotation;
    result.translation = inputData.position;
    if (inputData.cameraOffset != 0.0) {
        vec3 toCamera = -lfx_mesh_world_position(inputData.position, inputData);
        if (dot(toCamera, toCamera) > 0.0)
            result.translation += lfx_mesh_world_to_sim_direction(normalize(toCamera), inputData) * inputData.cameraOffset;
    }
    result.translation += inputData.meshOffsetWorld
            ? lfx_mesh_world_to_sim_direction(inputData.meshOffset, inputData)
            : result.rotation * (inputData.meshOffset * inputData.scale);
    return result;
}

LfxMeshTransform lfx_mesh_transform(LfxMeshSrt srt, LfxMeshTransformInput inputData) {
    LfxMeshTransform result;
    result.linear = srt.rotation * mat3(srt.scale.x, 0.0, 0.0, 0.0, srt.scale.y, 0.0, 0.0, 0.0, srt.scale.z);
    result.rotation = srt.rotation;
    result.position = srt.translation + inputData.worldOffset;
    if (inputData.localSpace) {
        result.linear = inputData.primitiveLinear * result.linear;
        result.rotation = inputData.primitiveRotation * result.rotation;
        result.position = inputData.primitiveLinear * srt.translation + inputData.primitivePosition;
    }
    result.inverseScale = vec3(1.0) / abs(srt.scale * (inputData.localSpace ? inputData.primitiveScale : vec3(1.0)));
    result.determinantSign = determinant(result.linear) < 0.0 ? -1.0 : 1.0;
    return result;
}

#endif
