#include "UvLayoutExchange.h"
#include "Misc/FileHelper.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/MemoryReader.h"

bool ReadUvLayoutInput(const FString& path, FUvLayoutMeshView& view, FUvLayoutOptions& options)
{
    TArray<uint8> bytes;
    if (!FFileHelper::LoadFileToArray(bytes, *path) || bytes.Num() < 24) return false;
    FMemoryReader input(bytes, true);
    uint32 magic, format, count, version;
    input << magic << format << count << options.Resolution << version << options.ComparisonThreshold;
    if (magic != 0x5655464c || format != 1 || count > MAX_int32 || count % 3 != 0
        || static_cast<int64>(count) * 32 + 24 != bytes.Num()
        || version > static_cast<uint32>(ELightmapUVVersion::Latest)
        || options.Resolution == 0 || (version >= static_cast<uint32>(ELightmapUVVersion::ForceLightmapPadding) && options.Resolution <= 2)
        || static_cast<uint64>(options.Resolution) - (version >= static_cast<uint32>(ELightmapUVVersion::ForceLightmapPadding) ? 2 : 0) > MAX_uint16
        || !FMath::IsFinite(options.ComparisonThreshold) || options.ComparisonThreshold < 0) return false;
    options.Version = static_cast<ELightmapUVVersion>(version);
    view.Positions.SetNumUninitialized(count);
    view.Normals.SetNumUninitialized(count);
    view.Coordinates.SetNumUninitialized(count);
    for (uint32 index = 0; index < count; index++)
    {
        FVector3f& position = view.Positions[index];
        FVector3f& normal = view.Normals[index];
        FVector2f& uv = view.Coordinates[index];
        input << position.X << position.Y << position.Z << normal.X << normal.Y << normal.Z << uv.X << uv.Y;
        if (position.ContainsNaN() || normal.ContainsNaN() || uv.ContainsNaN()) return false;
    }
    return !input.IsError();
}

bool WriteUvLayoutOutput(const FString& path, const FUvLayoutMeshView& view, bool packed)
{
    if (packed && view.Output.Num() != view.Positions.Num()) return false;
    FBufferArchive output;
    uint32 magic = 0x4f55464c, format = 1, count = packed ? view.GetNumIndices() : 0, status = packed ? 1 : 0;
    output << magic << format << count << status;
    if (packed)
    {
        for (const FVector2f& value : view.Output)
        {
            float x = value.X, y = value.Y;
            output << x << y;
        }
    }
    return FFileHelper::SaveArrayToFile(output, *path);
}
