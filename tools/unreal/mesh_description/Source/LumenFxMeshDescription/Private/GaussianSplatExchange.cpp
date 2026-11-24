#include "GaussianSplatExchange.h"
#include "GaussianSplat.h"
#include "GaussianSplatFormats.h"
#include "GaussianSplatShared.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/UObjectGlobals.h"

namespace
{
    void WriteFloat(FArchive& Archive, float Value)
    {
        Archive << Value;
    }

    void WriteVector(FArchive& Archive, const FVector3f& Value)
    {
        WriteFloat(Archive, Value.X);
        WriteFloat(Archive, Value.Y);
        WriteFloat(Archive, Value.Z);
    }

    void WriteVector(FArchive& Archive, const FVector4f& Value)
    {
        WriteFloat(Archive, Value.X);
        WriteFloat(Archive, Value.Y);
        WriteFloat(Archive, Value.Z);
        WriteFloat(Archive, Value.W);
    }

    bool IsFinite(const FVector3f& Value)
    {
        return FMath::IsFinite(Value.X) && FMath::IsFinite(Value.Y) && FMath::IsFinite(Value.Z);
    }

    bool IsFinite(const FVector4f& Value)
    {
        return FMath::IsFinite(Value.X) && FMath::IsFinite(Value.Y) && FMath::IsFinite(Value.Z) && FMath::IsFinite(Value.W);
    }

    void WriteQuantizationMetadata(FArchive& Archive, const FGaussianSplatFormatQuantized* Format)
    {
        const FVector3f Zero3 = FVector3f::ZeroVector;
        const FVector4f Zero4 = FVector4f::Zero();
        WriteVector(Archive, Format ? Format->PositionScaleBias[0] : Zero3);
        WriteVector(Archive, Format ? Format->PositionScaleBias[1] : Zero3);
        WriteVector(Archive, Format ? Format->ScaleScaleBias[0] : Zero3);
        WriteVector(Archive, Format ? Format->ScaleScaleBias[1] : Zero3);
        WriteVector(Archive, Format ? Format->ColorScaleBias[0] : Zero4);
        WriteVector(Archive, Format ? Format->ColorScaleBias[1] : Zero4);
        WriteVector(Archive, Format ? Format->SHScaleBias[0] : Zero3);
        WriteVector(Archive, Format ? Format->SHScaleBias[1] : Zero3);
    }

    bool RegisterMounts(const TSharedPtr<FJsonObject>& Request,
        TArray<TRefCountPtr<UE::PackageName::IMountPoint>>& Mounts)
    {
        const TSharedPtr<FJsonObject>* Roots = nullptr;
        if (!Request->TryGetObjectField(TEXT("mounts"), Roots) || !Roots || !Roots->IsValid()) return false;
        for (const auto& Root : (*Roots)->Values)
        {
            const FString RootName(*Root.Key);
            FString Directory;
            if (!RootName.StartsWith(TEXT("/")) || !RootName.EndsWith(TEXT("/")) || !Root.Value->TryGetString(Directory)
                || FPaths::IsRelative(Directory) || !FPaths::DirectoryExists(Directory)) return false;
            FPaths::NormalizeDirectoryName(Directory);
            Mounts.Add(FPackageName::RegisterMountPoint(RootName, Directory + TEXT("/")));
        }
        return true;
    }

    bool WriteRows(FArchive& Archive, const UGaussianSplat& Asset)
    {
        const int32 Count = Asset.GetNumSplats();
        const auto* FloatFormat = Asset.GetRuntimeData<FGaussianSplatFormatFloat>();
        const auto* QuantizedFormat = Asset.GetRuntimeData<FGaussianSplatFormatQuantized>();
        const FGaussianSplatFormat* Format = FloatFormat
            ? static_cast<const FGaussianSplatFormat*>(FloatFormat)
            : static_cast<const FGaussianSplatFormat*>(QuantizedFormat);
        if (!Format) return false;
        const int32 Degree = Format->GetSHDegree();
        if (Count < 0 || Degree < 0 || Degree > GaussianSplatCommon::MaxSupportedSHDegree
            || (FloatFormat == nullptr) == (QuantizedFormat == nullptr)) return false;
        const int32 Dimension = GaussianSplatCommon::GetSHDimension(Degree);
        const int32 TotalCount = Format->GetNumSplats();

        const uint64 RowWords = 14u + uint64(Dimension - 1) * 3u;
        const uint64 RequiredBytes = uint64(Count) * RowWords * sizeof(float);
        if (RequiredBytes > uint64(MAX_int32) - 156u || Count > TotalCount) return false;
        if (FloatFormat && (TotalCount > FloatFormat->BaseData.Num() || uint64(TotalCount) * Dimension > uint64(FloatFormat->SHData.Num())))
            return false;

        const uint64 QuantizedWordsPerSplat = uint64(Dimension) + 4u;
        if (QuantizedFormat && uint64(TotalCount) * QuantizedWordsPerSplat > uint64(QuantizedFormat->Data.Num())) return false;

        for (int32 Index = 0; Index < Count; ++Index)
        {
            FVector3f Position, Scale, Color;
            FQuat4f Rotation;
            float Opacity;
            const FVector3f* Coefficients = nullptr;
            TArray<FVector3f> DecodedCoefficients;

            if (FloatFormat)
            {
                const auto& Base = FloatFormat->BaseData[Index];
                Position = Base.Position;
                Scale = Base.Scale;
                Rotation = Base.Rotation;
                Opacity = Base.Opacity;
                Coefficients = FloatFormat->SHData.GetData() + uint64(Index) * Dimension;
                Color = Coefficients[0];
            }
            else
            {
                const uint32* Words = QuantizedFormat->Data.GetData() + uint64(Index) * QuantizedWordsPerSplat;
                Position = FGaussianSplatShared::Unpack_21_21_21(Words[0], Words[1],
                    QuantizedFormat->PositionScaleBias[0], QuantizedFormat->PositionScaleBias[1]);
                Scale = FGaussianSplatShared::Unpack_11_11_10_Exp(Words[2],
                    QuantizedFormat->ScaleScaleBias[0], QuantizedFormat->ScaleScaleBias[1]);
                Rotation = FGaussianSplatShared::UnpackQuat(Words[3]);
                const FVector4f ColorOpacity = FGaussianSplatShared::Unpack_8_8_8_8(Words[4],
                    QuantizedFormat->ColorScaleBias[0], QuantizedFormat->ColorScaleBias[1]);
                Color = FVector3f(ColorOpacity.X, ColorOpacity.Y, ColorOpacity.Z);
                Opacity = ColorOpacity.W;
                DecodedCoefficients.SetNumUninitialized(Dimension);
                DecodedCoefficients[0] = Color;
                for (int32 Coefficient = 1; Coefficient < Dimension; ++Coefficient)
                {
                    const FVector4f Value = FGaussianSplatShared::Unpack_8_8_8_8(Words[4 + Coefficient],
                        FVector4f(QuantizedFormat->SHScaleBias[0], 0.0f), FVector4f(QuantizedFormat->SHScaleBias[1], 0.0f));
                    DecodedCoefficients[Coefficient] = FVector3f(Value.X, Value.Y, Value.Z);
                }
                Coefficients = DecodedCoefficients.GetData();
            }

            if (!IsFinite(Position) || !IsFinite(Scale) || !IsFinite(Color) || !FMath::IsFinite(Opacity)
                || !FMath::IsFinite(Rotation.X) || !FMath::IsFinite(Rotation.Y) || !FMath::IsFinite(Rotation.Z) || !FMath::IsFinite(Rotation.W))
                return false;
            WriteVector(Archive, Position);
            WriteVector(Archive, Scale);
            WriteFloat(Archive, Rotation.X);
            WriteFloat(Archive, Rotation.Y);
            WriteFloat(Archive, Rotation.Z);
            WriteFloat(Archive, Rotation.W);
            WriteVector(Archive, Color);
            WriteFloat(Archive, Opacity);
            for (int32 Coefficient = 1; Coefficient < Dimension; ++Coefficient)
            {
                if (!IsFinite(Coefficients[Coefficient])) return false;
                WriteVector(Archive, Coefficients[Coefficient]);
            }
        }
        return !Archive.IsError();
    }
}

bool ExportNativeGaussianSplat(const FString& Input, const FString& Output)
{
    FString Json, Source;
    TSharedPtr<FJsonObject> Request;
    if (!FFileHelper::LoadFileToString(Json, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request)
        || !Request.IsValid() || !Request->TryGetStringField(TEXT("gaussian_splat"), Source)) return false;
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    if (!RegisterMounts(Request, Mounts)) return false;

    const UGaussianSplat* Asset = LoadObject<UGaussianSplat>(nullptr, *Source);
    if (!Asset || !Asset->GetRuntimeDataType() || !Asset->GetRuntimeDataType()->IsChildOf(FGaussianSplatFormat::StaticStruct())) return false;
    const FBox3f Bounds = Asset->GetBounds();
    if (!Bounds.IsValid || !IsFinite(Bounds.Min) || !IsFinite(Bounds.Max)) return false;

    const int32 Count = Asset->GetNumSplats();
    const auto* FloatFormat = Asset->GetRuntimeData<FGaussianSplatFormatFloat>();
    const auto* QuantizedFormat = Asset->GetRuntimeData<FGaussianSplatFormatQuantized>();
    const FGaussianSplatFormat* Format = FloatFormat
        ? static_cast<const FGaussianSplatFormat*>(FloatFormat)
        : static_cast<const FGaussianSplatFormat*>(QuantizedFormat);
    if (!Format || (FloatFormat == nullptr) == (QuantizedFormat == nullptr)) return false;
    const int32 Degree = Format->GetSHDegree();
    const int32 TotalCount = Format->GetNumSplats();
    const int32 CoordinateSpace = static_cast<int32>(Asset->GetSHCoordinateSpace());
    const int32 NativeFormat = QuantizedFormat ? 1 : 0;
    if (Count <= 0 || TotalCount < Count || Degree < 0 || Degree > GaussianSplatCommon::MaxSupportedSHDegree
        || CoordinateSpace < 0 || CoordinateSpace > 2) return false;

    FBufferArchive Archive;
    uint32 Magic = 0x5347464c; // ASCII LFGS in little-endian byte order.
    uint32 Version = 2;
    int32 HeaderCount = Count, HeaderDegree = Degree, HeaderCoordinateSpace = CoordinateSpace;
    int32 HeaderTotalCount = TotalCount, HeaderNativeFormat = NativeFormat;
    Archive << Magic << Version << HeaderCount << HeaderDegree << HeaderCoordinateSpace;
    WriteVector(Archive, Bounds.Min);
    WriteVector(Archive, Bounds.Max);
    Archive << HeaderTotalCount << HeaderNativeFormat;
    WriteQuantizationMetadata(Archive, QuantizedFormat);
    if (!WriteRows(Archive, *Asset)) return false;
    return !Archive.IsError() && FFileHelper::SaveArrayToFile(Archive, *Output);
}
