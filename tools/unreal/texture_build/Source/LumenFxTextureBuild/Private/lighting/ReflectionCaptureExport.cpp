#include "ReflectionCaptureExport.h"
#include "SpecularIntegrationExport.h"

#include "ColorManagement/ColorSpace.h"
#include "Components/ReflectionCaptureComponent.h"
#include "Dom/JsonObject.h"
#include "Dom/JsonValue.h"
#include "DynamicRHI.h"
#include "Engine/MapBuildDataRegistry.h"
#include "Math/Float16Color.h"
#include "Misc/FileHelper.h"
#include "Misc/Paths.h"
#include "RenderingThread.h"
#include "SceneProxies/ReflectionCaptureProxy.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/SoftObjectPath.h"
#include <initializer_list>

DEFINE_LOG_CATEGORY_STATIC(LogLumenFxReflectionCapture, Log, All);

namespace
{
    TArray<TSharedPtr<FJsonValue>> Values(std::initializer_list<double> Components)
    {
        TArray<TSharedPtr<FJsonValue>> Result;
        for (const double Value : Components) Result.Add(MakeShared<FJsonValueNumber>(Value));
        return Result;
    }

    struct FPreparedCapture
    {
        TSharedPtr<FJsonObject> SceneData;
        FXxHash64 SortGuid;
        float Radius;
        FString Output;
        const FReflectionCaptureData* BuildData;
        const FReflectionCaptureProxy* RegisteredProxy;
    };

    void SaveRegistrationOrder(TArray<FPreparedCapture>& Captures)
    {
        TArray<int32> Order;
        Order.Init(INDEX_NONE, Captures.Num());
        ENQUEUE_RENDER_COMMAND(LumenFxReadReflectionRegistrationOrder)(
            [&Captures, &Order](FRHICommandListImmediate&)
            {
                for (int32 Index = 0; Index < Captures.Num(); ++Index)
                    Order[Index] = Captures[Index].RegisteredProxy->PackedIndex;
            });
        FlushRenderingCommands();
        for (int32 Index = 0; Index < Captures.Num(); ++Index)
            if (Order[Index] != INDEX_NONE)
                Captures[Index].SceneData->SetNumberField(TEXT("registration_index"), Order[Index]);
    }

    bool SaveBuiltCubemap(const FPreparedCapture& Capture, bool bIncludeGF)
    {
        const auto& BuildData = *Capture.BuildData;
        uint32 Magic = 0x4b53464c, Version = bIncludeGF ? 2 : 1;
        uint32 Extent = BuildData.CubemapSize, Mips = FMath::CeilLogTwo(Extent) + 1;
        FBufferArchive Bytes;
        Bytes << Magic << Version << Extent << Mips;
        // Build data stores mip-major, then the six native face payloads in RGBA16F order.
        Bytes.Serialize(const_cast<uint8*>(BuildData.FullHDRCapturedData.GetData()), BuildData.FullHDRCapturedData.Num());
        if (bIncludeGF)
        {
            bool bSuccess = false;
            ENQUEUE_RENDER_COMMAND(LumenFxExportReflectionGF)([&Bytes, &bSuccess](FRHICommandListImmediate& RHICmdList)
            {
                bSuccess = AppendPreintegratedSpecularGF(RHICmdList, Bytes);
            });
            FlushRenderingCommands();
            if (!bSuccess) return false;
        }
        return !Bytes.IsError() && FFileHelper::SaveArrayToFile(Bytes, *Capture.Output);
    }
}

bool ExportReflectionCaptures(const FString& Input)
{
    FString Text, Output;
    TSharedPtr<FJsonObject> Request;
    const TArray<TSharedPtr<FJsonValue>>* Entries = nullptr;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()
        || !Request->TryGetStringField(TEXT("output"), Output) || FPaths::IsRelative(Output)
        || !FPaths::CollapseRelativeDirectories(Output)
        || !Request->TryGetArrayField(TEXT("captures"), Entries) || Entries->IsEmpty()) return false;
    if (!GDynamicRHI || RHIGetInterfaceType() == ERHIInterfaceType::Null)
    {
        UE_LOG(LogLumenFxReflectionCapture, Error, TEXT("Reflection capture export requires an active RHI for the native BRDF lookup"));
        return false;
    }

    TArray<FPreparedCapture> Captures;
    TSet<FString> Paths, Resources, Files;
    Files.Add(Output.ToLower());
    int32 CommonExtent = 0;
    const UWorld* RegisteredWorld = nullptr;
    bool bShareRegisteredWorld = true;
    for (const auto& Entry : *Entries)
    {
        const TSharedPtr<FJsonObject>* Capture = nullptr;
        FString ComponentPath, TextureFile, Resource;
        if (!Entry->TryGetObject(Capture)
            || !(*Capture)->TryGetStringField(TEXT("component"), ComponentPath)
            || !(*Capture)->TryGetStringField(TEXT("cubemap_output"), TextureFile) || FPaths::IsRelative(TextureFile)
            || !FPaths::CollapseRelativeDirectories(TextureFile)
            || !(*Capture)->TryGetStringField(TEXT("cubemap_resource"), Resource) || Resource.IsEmpty()
            || Paths.Contains(ComponentPath) || Files.Contains(TextureFile.ToLower()) || Resources.Contains(Resource)) return false;
        Paths.Add(ComponentPath);
        Resources.Add(Resource);
        Files.Add(TextureFile.ToLower());

        const FSoftObjectPath ComponentReference(ComponentPath);
        const auto* Component = ComponentReference.IsValid() ? Cast<UReflectionCaptureComponent>(ComponentReference.TryLoad()) : nullptr;
        const auto* Built = Component ? Component->GetMapBuildData() : nullptr;
        if (!Built || Built->CubemapSize <= 0 || !FMath::IsPowerOfTwo(Built->CubemapSize))
        {
            UE_LOG(LogLumenFxReflectionCapture, Error, TEXT("No built HDR cubemap for %s"), *ComponentPath);
            return false;
        }
        if (CommonExtent == 0) CommonExtent = Built->CubemapSize;
        if (CommonExtent != Built->CubemapSize)
        {
            UE_LOG(LogLumenFxReflectionCapture, Error, TEXT("Reflection capture extents must match within a scene array"));
            return false;
        }
        int64 ExpectedSize = 0;
        for (int32 Size = Built->CubemapSize; Size > 0; Size >>= 1)
            ExpectedSize += static_cast<int64>(Size) * Size * 6 * sizeof(FFloat16Color);
        if (Built->FullHDRCapturedData.Num() != ExpectedSize)
        {
            UE_LOG(LogLumenFxReflectionCapture, Error, TEXT("Invalid built HDR mip/face payload for %s"), *ComponentPath);
            return false;
        }

        FReflectionCaptureProxy Proxy(Component);
        const FVector Position = Component->GetComponentLocation();
        auto Data = MakeShared<FJsonObject>();
        Data->SetStringField(TEXT("source_component"), ComponentPath);
        Data->SetStringField(TEXT("texture"), Resource);
        Data->SetStringField(TEXT("shape"), Proxy.Shape == EReflectionCaptureShape::Sphere ? TEXT("sphere")
            : Proxy.Shape == EReflectionCaptureShape::Box ? TEXT("box") : TEXT("plane"));
        Data->SetArrayField(TEXT("position"), Values({Position.X, Position.Y, Position.Z}));
        Data->SetNumberField(TEXT("radius"), Proxy.InfluenceRadius);
        Data->SetNumberField(TEXT("brightness"), Proxy.Brightness);
        Data->SetNumberField(TEXT("average_brightness"), Built->AverageBrightness);
        Data->SetArrayField(TEXT("capture_offset"), Values({Proxy.CaptureOffset.X, Proxy.CaptureOffset.Y, Proxy.CaptureOffset.Z}));
        if (Proxy.Shape == EReflectionCaptureShape::Box)
        {
            TArray<TSharedPtr<FJsonValue>> RelativeWorldToBox;
            for (int32 Row = 0; Row < 3; ++Row)
                for (int32 Column = 0; Column < 3; ++Column)
                    RelativeWorldToBox.Add(MakeShared<FJsonValueNumber>(Proxy.BoxTransform.M[Row][Column]));
            Data->SetArrayField(TEXT("box_transform"), MoveTemp(RelativeWorldToBox));
            Data->SetArrayField(TEXT("box_scales"), Values({Proxy.BoxScales.X, Proxy.BoxScales.Y, Proxy.BoxScales.Z}));
            Data->SetNumberField(TEXT("box_transition"), Proxy.BoxTransitionDistance);
        }
        else if (Proxy.Shape == EReflectionCaptureShape::Plane)
        {
            const auto& Plane = Proxy.LocalReflectionPlane;
            const auto& Axis = Proxy.ReflectionXAxisAndYScale;
            Data->SetArrayField(TEXT("plane"), Values({Plane.X, Plane.Y, Plane.Z, Plane.W}));
            Data->SetArrayField(TEXT("plane_axis"), Values({Axis.X, Axis.Y, Axis.Z, Axis.W}));
        }
        const UWorld* World = Component->GetWorld();
        if (Captures.IsEmpty()) RegisteredWorld = World;
        bShareRegisteredWorld &= World && World == RegisteredWorld && Component->SceneProxy;
        Captures.Add({Data, Proxy.Guid, Proxy.InfluenceRadius, MoveTemp(TextureFile), Built, Component->SceneProxy});
    }

    if (bShareRegisteredWorld && !Captures.IsEmpty()) SaveRegistrationOrder(Captures);
    Captures.Sort([](const FPreparedCapture& A, const FPreparedCapture& B)
    {
        return A.Radius != B.Radius ? A.Radius < B.Radius : A.SortGuid < B.SortGuid;
    });
    TArray<TSharedPtr<FJsonValue>> Results;
    for (const auto& Capture : Captures)
    {
        if (!SaveBuiltCubemap(Capture, Results.IsEmpty())) return false;
        Results.Add(MakeShared<FJsonValueObject>(Capture.SceneData));
    }
    const auto Luma = UE::Color::FColorSpace::GetWorking().GetLuminanceFactors();
    auto Data = MakeShared<FJsonObject>();
    Data->SetNumberField(TEXT("mip_count"), FMath::CeilLogTwo(CommonExtent) + 1);
    Data->SetArrayField(TEXT("luminance_weights"), Values({Luma.R, Luma.G, Luma.B}));
    Data->SetArrayField(TEXT("captures"), MoveTemp(Results));
    FString Serialized;
    return FJsonSerializer::Serialize(Data, TJsonWriterFactory<>::Create(&Serialized))
        && FFileHelper::SaveStringToFile(Serialized, *Output, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);
}
