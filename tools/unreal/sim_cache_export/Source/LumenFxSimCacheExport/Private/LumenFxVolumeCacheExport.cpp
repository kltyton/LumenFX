#include "LumenFxVolumeCacheExport.h"

#include "Misc/Compression.h"
#include "Misc/Paths.h"
#include "Serialization/Archive.h"
#include "VolumeCache.h"

DEFINE_LOG_CATEGORY_STATIC(LogLumenFxVolumeCache, Log, All);

#if LUMENFX_WITH_OPENVDB
THIRD_PARTY_INCLUDES_START
UE_PUSH_MACRO("check")
#undef check
#include <openvdb/openvdb.h>
#include <openvdb/io/File.h>
#include <openvdb/math/Half.h>
#include <openvdb/tools/Dense.h>
using VolumeColor = openvdb::math::Vec4<openvdb::math::internal::half>;
OPENVDB_IS_POD(VolumeColor)
UE_POP_MACRO("check")
THIRD_PARTY_INCLUDES_END

namespace
{
    using VolumeTree = openvdb::tree::Tree4<VolumeColor, 5, 4, 3>::Type;
    using VolumeGrid = openvdb::Grid<VolumeTree>;
    using DenseVolume = openvdb::tools::Dense<VolumeColor, openvdb::tools::MemoryLayout::LayoutXYZ>;

    FString ResolveFrame(const FString& Pattern, int32 Frame, const FString& SavedDirectory)
    {
        const TMap<FString, FStringFormatArg> Args = {
            {TEXT("SavedDir"), SavedDirectory},
            {TEXT("FrameIndex"), FString::Printf(TEXT("%03d"), Frame)}
        };
        FString Path = FString::Format(*Pattern, Args);
        Path.ReplaceInline(TEXT("//"), TEXT("/"));
        return Path;
    }

    bool DecodeFrame(const FString& Path, const FIntVector& Resolution, TArray<uint8>& Compressed)
    {
        try
        {
            openvdb::io::File File(TCHAR_TO_UTF8(*Path));
            File.open(false);
            const openvdb::MetaMap::Ptr Metadata = File.getMetadata();
            const FIntVector SourceSize(Metadata->metaValue<int>("DenseResolutionX"),
                Metadata->metaValue<int>("DenseResolutionY"), Metadata->metaValue<int>("DenseResolutionZ"));
            if (SourceSize != Resolution || Metadata->metaValue<bool>("FloatGridStorage"))
                return false;
            const auto Color = openvdb::gridPtrCast<VolumeGrid>(File.readGrid(openvdb::Name("Color")));
            if (!Color)
                return false;

            const auto Bounds = openvdb::CoordBBox(0, 0, 0, Resolution.X - 1, Resolution.Y - 1, Resolution.Z - 1);
            DenseVolume Dense(Bounds, VolumeColor(openvdb::math::internal::half(0.0f)));
            openvdb::tools::CopyToDense<VolumeTree, DenseVolume> Copy(Color->tree(), Dense);
            Copy.copy();
            const int64 Uncompressed = int64(Resolution.X) * Resolution.Y * Resolution.Z * 8;
            int64 CompressedSize;
            static_assert(sizeof(VolumeColor) == 8, "Volume Cache uses four packed IEEE 754 binary16 values");
            if (!FCompression::CompressMemoryBound(NAME_Zlib, CompressedSize, Uncompressed) || CompressedSize > MAX_int32)
                return false;
            Compressed.SetNumUninitialized(static_cast<int32>(CompressedSize));
            if (!FCompression::CompressMemory(NAME_Zlib, Compressed.GetData(), CompressedSize, Dense.data(), Uncompressed))
                return false;
            Compressed.SetNum(static_cast<int32>(CompressedSize), EAllowShrinking::No);
            return true;
        }
        catch (const openvdb::Exception& Error)
        {
            UE_LOG(LogLumenFxVolumeCache, Warning, TEXT("%s: %s"), *Path, UTF8_TO_TCHAR(Error.what()));
            return false;
        }
    }
}
#endif

bool WriteLumenFxVolumeCache(FArchive& Archive, const UVolumeCache& Cache, const FString& SavedDirectory)
{
#if LUMENFX_WITH_OPENVDB
    const FIntVector Size = Cache.Resolution;
    if (Cache.CacheType != EVolumeCacheType::OpenVDB || Size.X <= 0 || Size.Y <= 0 || Size.Z <= 0
        || Size.X > MAX_int32 / 8 / Size.Y / Size.Z || Cache.FrameRangeStart > Cache.FrameRangeEnd)
        return false;

    openvdb::initialize();
    if (!VolumeGrid::isRegistered())
        VolumeGrid::registerGrid();

    uint8 Magic[] = {'L', 'F', 'V', 'C'};
    Archive.Serialize(Magic, sizeof(Magic));
    uint32 Version = 1;
    int32 Width = Size.X, Height = Size.Y, Depth = Size.Z, Count = 0;
    Archive << Version << Width << Height << Depth;
    const int64 CountOffset = Archive.Tell();
    Archive << Count;

    TArray<uint8> Bytes;
    for (int64 Index = Cache.FrameRangeStart; Index <= Cache.FrameRangeEnd; ++Index)
    {
        int32 Frame = static_cast<int32>(Index);
        FString Path = ResolveFrame(Cache.FilePath, Frame, SavedDirectory);
        if (!DecodeFrame(Path, Size, Bytes))
        {
            UE_LOG(LogLumenFxVolumeCache, Warning, TEXT("Cannot read half RGBA Volume Cache frame %d from %s"), Frame, *Path);
            continue;
        }
        int32 Length = Bytes.Num();
        if (Archive.Tell() + int64(Length) + sizeof(int32) * 2 >= MAX_int32)
            return false;
        Archive << Frame << Length;
        Archive.Serialize(Bytes.GetData(), Length);
        ++Count;
    }
    const int64 End = Archive.Tell();
    Archive.Seek(CountOffset);
    Archive << Count;
    Archive.Seek(End);
    return !Archive.IsError() && Count > 0;
#else
    UE_LOG(LogLumenFxVolumeCache, Error, TEXT("This Unreal export target lacks OpenVDB"));
    return false;
#endif
}
