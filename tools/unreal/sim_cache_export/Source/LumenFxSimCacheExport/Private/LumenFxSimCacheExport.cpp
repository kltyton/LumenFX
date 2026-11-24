#include "CoreMinimal.h"
#include "RequiredProgramMainCPPInclude.h"
#include "Dom/JsonObject.h"
#include "Misc/CommandLine.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"
#include "Misc/ScopeExit.h"
#include "Modules/ModuleManager.h"
#include "NiagaraSimCache.h"
#include "NiagaraSimCacheDebugData.h"
#include "NiagaraDataInterfaceArray.h"
#include "DataInterface/NiagaraDataInterfaceSimpleCounter.h"
#include "DataInterface/NDIRenderTargetSimCacheData.h"
#include "DataInterface/NiagaraDataInterfaceDataChannelWrite.h"
#include "DataInterface/NiagaraDataInterfaceMemoryBuffer.h"
#include "DataInterface/NiagaraDataInterfaceDynamicMeshSimCacheData.h"
#include "Materials/MaterialInterface.h"
#include "SparseVolumeTexture/SparseVolumeTexture.h"
#include "LumenFxSparseVolumeCache.h"
#include "LumenFxVolumeCacheExport.h"
#include "LumenFxDataTableExport.h"
#include "Engine/DataTable.h"
#include "VolumeCache.h"
#include "UObject/UnrealType.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"

IMPLEMENT_APPLICATION(LumenFxSimCacheExport, "LumenFxSimCacheExport");

namespace
{
    void Text(FArchive& Ar, const FString& String)
    {
        const FTCHARToUTF8 Utf8(*String);
        uint32 Size = Utf8.Length();
        Ar << Size;
        if (Size != 0) Ar.Serialize(const_cast<ANSICHAR*>(Utf8.Get()), Size);
    }

    template<typename T>
    void Vector(FArchive& Ar, const T& Value)
    {
        double X = Value.X, Y = Value.Y, Z = Value.Z;
        Ar << X << Y << Z;
    }

    void Bounds(FArchive& Ar, const FBox& Box)
    {
        uint32 Valid = Box.IsValid ? 1 : 0;
        Ar << Valid;
        if (Valid) { Vector(Ar, Box.Min); Vector(Ar, Box.Max); }
    }

    void Range(FArchive& Ar, uint16 Offset, uint16 Count)
    {
        int32 Start = Offset == MAX_uint16 ? -1 : Offset;
        uint32 Size = Count;
        Ar << Start << Size;
    }

    bool Layout(FArchive& Ar, const FNiagaraSimCacheDataBuffersLayout& Data)
    {
        Text(Ar, Data.LayoutName.ToString());
        uint32 Flags = (Data.bLocalSpace ? 1 : 0) | (Data.SimTarget == ENiagaraSimTarget::GPUComputeSim ? 2 : 0);
        uint32 Floats = Data.FloatCount, Ints = Data.Int32Count, Halfs = Data.HalfCount, Fields = Data.Variables.Num();
        int32 Velocity = Data.bAllowVelocityExtrapolation && Data.ComponentVelocity != MAX_uint16 ? Data.ComponentVelocity : -1;
        Ar << Flags << Floats << Ints << Halfs << Velocity << Fields;
        for (const auto& Field : Data.Variables)
        {
            const FNiagaraTypeDefinition& Type = Field.Variable.GetType();
            const UObject* TypeObject = Type.GetEnum();
            if (!TypeObject) TypeObject = Type.GetClass();
            if (!TypeObject) TypeObject = Type.GetStruct();
            if (!TypeObject) return false;
            Text(Ar, Field.Variable.GetName().ToString());
            Text(Ar, TypeObject->GetPathName());
            Range(Ar, Field.FloatOffset, Field.FloatCount);
            Range(Ar, Field.Int32Offset, Field.Int32Count);
            Range(Ar, Field.HalfOffset, Field.HalfCount);
            uint32 FieldFlags = (Data.RebaseVariableNames.Contains(Field.Variable.GetName()) ? 1 : 0)
                | (Data.bAllowInterpolation && Data.InterpVariableNames.Contains(Field.Variable.GetName()) ? 2 : 0);
            Ar << FieldFlags;
        }
        return !Ar.IsError();
    }

    int32 ArrayWidth(FName Class)
    {
        if (Class == TEXT("NiagaraDataInterfaceArrayFloat") || Class == TEXT("NiagaraDataInterfaceArrayInt32")
            || Class == TEXT("NiagaraDataInterfaceArrayUInt8") || Class == TEXT("NiagaraDataInterfaceArrayBool")) return 1;
        if (Class == TEXT("NiagaraDataInterfaceArrayFloat2") || Class == TEXT("NiagaraDataInterfaceArrayNiagaraID")) return 2;
        if (Class == TEXT("NiagaraDataInterfaceArrayFloat3") || Class == TEXT("NiagaraDataInterfaceArrayPosition")) return 3;
        if (Class == TEXT("NiagaraDataInterfaceArrayFloat4") || Class == TEXT("NiagaraDataInterfaceArrayColor")
            || Class == TEXT("NiagaraDataInterfaceArrayQuat")) return 4;
        if (Class == TEXT("NiagaraDataInterfaceArrayMatrix")) return 16;
        return 0;
    }

    bool ArrayFrame(FArchive& Ar, const UNDIArraySimCacheData& Data, TConstArrayView<FNDIArraySimCacheDataFrame> Frames,
        int32 Index, int32 Width, bool bByte, bool bBool)
    {
        if (!Frames.IsValidIndex(Index))
        {
            int32 Missing = -1;
            Ar << Missing;
            return true;
        }
        const auto& Frame = Frames[Index];
        const int64 WordCount = int64(Frame.NumElements) * Width;
        const int32 WordSize = bByte ? 1 : sizeof(uint32);
        if (WordCount < 0 || WordCount > MAX_int32 || (WordCount != 0
            && (Frame.DataOffset < 0 || int64(Frame.DataOffset) + WordCount * WordSize > Data.BufferData.Num())))
        {
            return false;
        }
        int32 Count = int32(WordCount);
        Ar << Count;
        for (int32 WordIndex = 0; WordIndex < Count; ++WordIndex)
        {
            const uint8* Address = Data.BufferData.GetData() + Frame.DataOffset + int64(WordIndex) * WordSize;
            uint32 Word;
            if (bByte)
            {
                Word = bBool && *Address != 0 ? MAX_uint32 : *Address;
            }
            else
            {
                FMemory::Memcpy(&Word, Address, sizeof(Word));
            }
            Ar << Word;
        }
        return !Ar.IsError();
    }

    int32 TargetFormat(EPixelFormat PixelFormat)
    {
        switch (PixelFormat)
        {
        case PF_G8: return 0;
        case PF_R8G8: return 1;
        case PF_B8G8R8A8: return 2;
        case PF_R16F: return 4;
        case PF_G16R16F: return 5;
        case PF_FloatRGBA: return 6;
        case PF_R32_FLOAT: return 7;
        case PF_G32R32F: return 8;
        case PF_A32B32G32R32F: return 9;
        case PF_A2B10G10R10: return 10;
        default: return -1;
        }
    }

    bool TextureFrame(FArchive& Ar, const UNDIRenderTargetSimCacheData& Texture, int32 Frame)
    {
        if (!Texture.IsValidFrame(Frame))
        {
            int32 Missing = -1;
            Ar << Missing;
            return true;
        }
        FIntVector Size = Texture.GetTextureSize(Frame);
        const EPixelFormat PixelFormat = Texture.GetTextureFormat(Frame);
        int32 Format = TargetFormat(PixelFormat);
        if (Size.X == 0 && Size.Y == 0 && !Texture.HasPixelData(Frame))
        {
            int32 EmptyFormat = 2, EmptySize = 0;
            Ar << EmptyFormat << EmptySize << EmptySize << EmptySize;
            return true;
        }
        if (Format < 0 || Size.X <= 0 || Size.Y <= 0 || Size.Z != 1) return false;
        auto Pixels = Texture.HasPixelData(Frame) ? Texture.GetPixelData(Frame) : TArray<uint8>();
        int32 Bytes = Pixels.Num();
        if (Bytes != 0 && int64(Size.X) * Size.Y * GPixelFormats[PixelFormat].BlockBytes != Bytes) return false;
        if (PixelFormat == PF_B8G8R8A8)
        {
            for (int32 Offset = 0; Offset < Bytes; Offset += 4) Swap(Pixels[Offset], Pixels[Offset + 2]);
        }
        Ar << Format << Size.X << Size.Y << Bytes;
        if (Bytes != 0) Ar.Serialize(Pixels.GetData(), Bytes);
        return !Ar.IsError();
    }

    enum class EChannelScalar : uint8 { Float, Double, Int, Half };
    struct FChannelComponent
    {
        int32 Offset;
        EChannelScalar Type;
    };

    bool ChannelComponents(const UScriptStruct* Struct, int32 BaseOffset, TArray<FChannelComponent>& Components)
    {
        for (TFieldIterator<FProperty> It(Struct, EFieldIteratorFlags::IncludeSuper); It; ++It)
        {
            const FProperty* Property = *It;
            if (Property->ArrayDim != 1) return false;
            const int32 Offset = BaseOffset + Property->GetOffset_ForInternal();
            if (Property->IsA(FDoubleProperty::StaticClass())) Components.Add({Offset, EChannelScalar::Double});
            else if (Property->IsA(FFloatProperty::StaticClass())) Components.Add({Offset, EChannelScalar::Float});
            else if (Property->IsA(FUInt16Property::StaticClass())) Components.Add({Offset, EChannelScalar::Half});
            else if (Property->IsA(FIntProperty::StaticClass()) || Property->IsA(FBoolProperty::StaticClass()))
                Components.Add({Offset, EChannelScalar::Int});
            else if (const auto* Child = CastField<FStructProperty>(Property))
            {
                if (!ChannelComponents(Child->Struct, Offset, Components)) return false;
            }
            else return false;
        }
        return true;
    }

    template<typename T>
    T ReadChannelScalar(const uint8* Address)
    {
        T Value;
        FMemory::Memcpy(&Value, Address, sizeof(T));
        return Value;
    }

    bool ChannelColumn(FArchive& Ar, const FNDIDataChannelWriteSimCacheFrameBuffer& Buffer, int32 Rows)
    {
        const auto SourceType = Buffer.SourceVar.GetType();
        const UScriptStruct* GameStruct = SourceType.GetScriptStruct();
        const bool bPosition = SourceType == FNiagaraTypeDefinition::GetPositionDef();
        const UScriptStruct* SimulationStruct = GameStruct;
        TArray<FChannelComponent> Components;
        if (bPosition)
        {
            Components = {{0, EChannelScalar::Double}, {sizeof(double), EChannelScalar::Double},
                {2 * sizeof(double), EChannelScalar::Double}};
        }
        else
        {
            if (!GameStruct || !ChannelComponents(GameStruct, 0, Components)) return false;
            SimulationStruct = FNiagaraTypeHelper::FindNiagaraFriendlyTopLevelStruct(SourceType.GetScriptStruct(), ENiagaraStructConversion::Simulation);
        }
        const UObject* Type = SourceType.IsEnum() ? static_cast<const UObject*>(SourceType.GetEnum())
            : static_cast<const UObject*>(SimulationStruct);
        if (!Type || Buffer.Size < 0 || Buffer.Data.Num() != int64(Buffer.Size) * Rows) return false;
        Text(Ar, Buffer.SourceVar.GetName().ToString());
        Text(Ar, Type->GetPathName());
        int32 Width = Components.Num();
        Ar << Width;
        for (int32 Row = 0; Row < Rows; ++Row)
        {
            for (const auto& Component : Components)
            {
                const int32 Bytes = Component.Type == EChannelScalar::Double ? sizeof(double)
                    : Component.Type == EChannelScalar::Half ? sizeof(uint16) : sizeof(uint32);
                if (Component.Offset < 0 || Component.Offset + Bytes > Buffer.Size) return false;
                const uint8* Address = Buffer.Data.GetData() + int64(Row) * Buffer.Size + Component.Offset;
                double Value = 0;
                switch (Component.Type)
                {
                case EChannelScalar::Float: Value = ReadChannelScalar<float>(Address); break;
                case EChannelScalar::Double: Value = ReadChannelScalar<double>(Address); break;
                case EChannelScalar::Int: Value = ReadChannelScalar<int32>(Address); break;
                case EChannelScalar::Half:
                {
                    FFloat16 Half;
                    Half.Encoded = ReadChannelScalar<uint16>(Address);
                    Value = Half.GetFloat();
                    break;
                }
                }
                Ar << Value;
            }
        }
        return !Ar.IsError();
    }

    bool DynamicMeshFrame(FArchive& Ar, const FNDIDynamicMeshSimCacheFrame* Mesh)
    {
        uint32 Present = Mesh ? 1 : 0;
        Ar << Present;
        if (!Mesh) return true;
        if (Mesh->PositionOffset != 0 || (Mesh->VertexBufferSize & 3u) != 0
            || (Mesh->IndexData.Num() & 3) != 0 || (Mesh->VertexData.Num() & 3) != 0) return false;
        uint32 Triangles = Mesh->NumTriangles, Vertices = Mesh->NumVertices, UVs = Mesh->NumTexCoords;
        auto Offset = [](uint32 Value) -> int32 { return Value == uint32(INDEX_NONE) ? -1 : int32(Value >> 2u); };
        int32 Tangent = Offset(Mesh->TangentBasisOffset), UV = Offset(Mesh->TexCoordOffset), Color = Offset(Mesh->ColorOffset);
        uint32 VertexWords = Mesh->VertexBufferSize >> 2u;
        Ar << Triangles << Vertices << UVs << Tangent << UV << Color << VertexWords;
        Bounds(Ar, Mesh->LocalBounds);
        int32 Sections = Mesh->Sections.Num();
        Ar << Sections;
        for (const auto& Section : Mesh->Sections)
        {
            uint32 First = Section.TriangleOffset, Maximum = Section.MaxTriangles, Allocated = Section.AllocatedTriangles;
            Ar << First << Maximum << Allocated;
            Text(Ar, Section.Material ? Section.Material->GetPathName() : FString());
        }
        int32 Indices = Mesh->IndexData.Num() / sizeof(uint32), Attributes = Mesh->VertexData.Num() / sizeof(uint32);
        Ar << Indices << Attributes;
        for (int32 Word = 0; Word < Indices; ++Word)
        {
            uint32 Value = ReadChannelScalar<uint32>(Mesh->IndexData.GetData() + Word * sizeof(uint32));
            Ar << Value;
        }
        for (int32 Word = 0; Word < Attributes; ++Word)
        {
            uint32 Value = ReadChannelScalar<uint32>(Mesh->VertexData.GetData() + Word * sizeof(uint32));
            Ar << Value;
        }
        return !Ar.IsError();
    }

    bool Interface(FArchive& Ar, const FNiagaraVariableBase& Variable, const UObject& Storage, int32 Frames, bool bRebase)
    {
        const UClass* Class = Variable.GetType().GetClass();
        if (!Class) return false;
        const FName ClassName = Class->GetFName();
        Text(Ar, Variable.GetName().ToString());
        Text(Ar, Class->GetPathName());
        if (const auto* Array = Cast<UNDIArraySimCacheData>(&Storage))
        {
            const int32 Width = ArrayWidth(ClassName);
            if (Width == 0) return false;
            const bool bBool = ClassName == TEXT("NiagaraDataInterfaceArrayBool");
            const bool bByte = bBool || ClassName == TEXT("NiagaraDataInterfaceArrayUInt8");
            uint32 Tag = 1;
            Ar << Tag;
            for (int32 Frame = 0; Frame < Frames; ++Frame)
            {
                if (!ArrayFrame(Ar, *Array, Array->CpuFrameData, Frame, Width, bByte, bBool)
                    || !ArrayFrame(Ar, *Array, Array->GpuFrameData, Frame, Width, bByte, bBool)) return false;
            }
            return true;
        }
        if (const auto* Counter = Cast<UNDISimpleCounterSimCacheData>(&Storage))
        {
            if (Counter->Values.Num() != int64(Frames) * 2) return false;
            uint32 Tag = 2;
            Ar << Tag;
            for (int32 Value : Counter->Values) Ar << Value;
            return !Ar.IsError();
        }
        if (const auto* Memory = Cast<UNDIMemoryBufferSimCacheData>(&Storage))
        {
            if (Memory->FrameData.Num() != Frames) return false;
            uint32 Tag = 1;
            Ar << Tag;
            auto Words = [&](int32 Offset, int32 Size)
            {
                if (Size < 0 || (Size > 0 && (Offset < 0 || int64(Offset) + Size > Memory->BufferData.Num()))) return false;
                int32 Count = Size == 0 ? -1 : Size;
                Ar << Count;
                for (int32 Word = 0; Word < Size; ++Word)
                {
                    uint32 Value = Memory->BufferData[Offset + Word];
                    Ar << Value;
                }
                return !Ar.IsError();
            };
            for (const auto& Frame : Memory->FrameData)
                if (!Words(Frame.CpuDataOffset, Frame.CpuBufferSize) || !Words(Frame.GpuDataOffset, Frame.GpuBufferSize)) return false;
            return true;
        }
        if (const auto* Texture = Cast<UNDIRenderTargetSimCacheData>(&Storage))
        {
            if (ClassName == TEXT("NiagaraDataInterfaceRenderTarget2D"))
            {
                uint32 Tag = 3;
                Ar << Tag;
                for (int32 Frame = 0; Frame < Frames; ++Frame)
                    if (!TextureFrame(Ar, *Texture, Frame)) return false;
                return true;
            }
            if (ClassName == TEXT("NiagaraDataInterfaceRenderTargetVolume"))
            {
                uint32 Tag = 6;
                Ar << Tag;
                for (int32 Frame = 0; Frame < Frames; ++Frame)
                {
                    if (!Texture->IsValidFrame(Frame)) return false;
                    FIntVector Size = Texture->GetTextureSize(Frame);
                    EPixelFormat Format = Texture->GetTextureFormat(Frame);
                    if (Texture->HasPixelData(Frame))
                    {
                        if (!WriteLumenFxDenseVolumeFrame(Ar, Size, Format, TargetFormat(Format), Texture->GetPixelData(Frame))) return false;
                    }
                    else if (!WriteLumenFxVolumeControls(Ar, Size, TargetFormat(Format))) return false;
                }
                return true;
            }
            return false;
        }
        if (const auto* Volume = Cast<UAnimatedSparseVolumeTexture>(&Storage))
        {
            if (ClassName != TEXT("NiagaraDataInterfaceRenderTargetVolume")) return false;
            uint32 Tag = 6;
            Ar << Tag;
            for (int32 Frame = 0; Frame < Frames; ++Frame)
                if (!WriteLumenFxSparseVolumeFrame(Ar, *Volume, Frame, TargetFormat(Volume->GetFormat(0)))) return false;
            return true;
        }
        if (const auto* Channel = Cast<UNDIDataChannelWriteSimCacheData>(&Storage))
        {
            if (Channel->FrameData.Num() != Frames) return false;
            uint32 Tag = 4;
            Ar << Tag;
            for (const auto& Frame : Channel->FrameData)
            {
                int32 Rows = Frame.NumElements, Columns = Frame.VariableData.Num();
                uint32 Flags = (Frame.bVisibleToGame ? 1 : 0) | (Frame.bVisibleToCPUSims ? 2 : 0)
                    | (Frame.bVisibleToGPUSims ? 4 : 0) | (bRebase ? 8 : 0);
                if (Rows < 0) return false;
                Ar << Rows << Columns << Flags;
                for (const auto& Buffer : Frame.VariableData)
                    if (!ChannelColumn(Ar, Buffer, Rows)) return false;
            }
            return true;
        }
        if (const auto* Dynamic = Cast<UNiagaraDataInterfaceDynamicMeshSimCacheData>(&Storage))
        {
            uint32 Tag = 5;
            Ar << Tag;
            for (int32 Frame = 0; Frame < Frames; ++Frame)
                if (!DynamicMeshFrame(Ar, Dynamic->GetFrameData(Frame, ENiagaraSimTarget::CPUSim))
                    || !DynamicMeshFrame(Ar, Dynamic->GetFrameData(Frame, ENiagaraSimTarget::GPUComputeSim))) return false;
            return true;
        }
        return false;
    }

    bool DebugStore(FArchive& Ar, const FNiagaraParameterStore& Store)
    {
        auto Variables = Store.ReadParameterVariables();
        int32 Count = Variables.Num();
        Ar << Count;
        for (const FNiagaraVariableWithOffset& Variable : Variables)
        {
            const auto& Type = Variable.GetType();
            const UObject* TypeObject = Type.GetEnum();
            uint32 Underlying = TypeObject ? 3 : 2;
            if (!TypeObject && Type.GetClass()) { TypeObject = Type.GetClass(); Underlying = 1; }
            if (!TypeObject) TypeObject = Type.GetStruct();
            if (!TypeObject) return false;
            uint32 Flags = Type.GetFlags();
            uint32 Storage = Type.IsDataInterface() ? 1 : Type.IsUObject() ? 2 : 0;
            Text(Ar, Variable.GetName().ToString());
            Text(Ar, TypeObject->GetPathName());
            Ar << Underlying << Flags << Storage;
            if (Storage == 0)
            {
                int32 Bytes = Type.GetSize();
                const auto& Values = Store.GetParameterDataArray();
                if (Variable.Offset < 0 || Bytes < 0 || int64(Variable.Offset) + Bytes > Values.Num()) return false;
                Ar << Bytes;
                if (Bytes > 0) Ar.Serialize(const_cast<uint8*>(Values.GetData() + Variable.Offset), Bytes);
            }
            else
            {
                const UObject* Object;
                if (Storage == 1)
                {
                    const auto& Values = Store.GetDataInterfaces();
                    if (!Values.IsValidIndex(Variable.Offset)) return false;
                    Object = Values[Variable.Offset];
                }
                else
                {
                    const auto& Values = Store.GetUObjects();
                    if (!Values.IsValidIndex(Variable.Offset)) return false;
                    Object = Values[Variable.Offset].Get();
                }
                uint32 Present = Object ? 1 : 0;
                Ar << Present;
                if (Object)
                {
                    Text(Ar, Object->GetPathName());
                    Text(Ar, Object->GetClass()->GetPathName());
                }
            }
        }
        return !Ar.IsError();
    }

    template <typename CacheDebugData>
    bool DebugData(FArchive& Ar, const CacheDebugData& Data, int32 FrameCount)
    {
        if (Data.Frames.Num() != FrameCount) return false;
        const auto& State = Data.SystemStateData;
        uint32 Flags = (State.bRunSpawnScript ? 1 : 0) | (State.bRunUpdateScript ? 2 : 0)
            | (State.bIgnoreSystemState ? 4 : 0) | (State.bRecalculateDurationEachLoop ? 8 : 0)
            | (State.bLoopDelayEnabled ? 16 : 0) | (State.bDelayFirstLoopOnly ? 32 : 0)
            | (State.bRecalculateDelayEachLoop ? 64 : 0);
        uint32 Inactive = uint32(State.InactiveResponse), Behavior = uint32(State.LoopBehavior);
        int32 Loops = State.LoopCount;
        float DurationMin = State.LoopDuration.Min, DurationMax = State.LoopDuration.Max;
        float DelayMin = State.LoopDelay.Min, DelayMax = State.LoopDelay.Max;
        Ar << Flags << Inactive << Behavior << Loops << DurationMin << DurationMax << DelayMin << DelayMax;
        for (int32 FrameIndex = 0; FrameIndex < FrameCount; ++FrameIndex)
        {
            const auto& Frame = Data.Frames[FrameIndex];
            TArray<FString> Names;
            if constexpr (requires { Frame.ParameterStoreIndices; })
                Frame.ParameterStoreIndices.GetKeys(Names);
            else
                Frame.DebugParameterStores.GetKeys(Names);
            Names.Sort();
            int32 Count = Names.Num();
            Ar << Count;
            for (const auto& Name : Names)
            {
                Text(Ar, Name);
                const FNiagaraParameterStore* Store;
                if constexpr (requires { Frame.ParameterStoreIndices; })
                    Store = Data.FindParameterStore(FrameIndex, Name);
                else
                    Store = Frame.DebugParameterStores.Find(Name);
                if (!Store || !DebugStore(Ar, *Store)) return false;
            }
        }
        return !Ar.IsError();
    }

    bool Buffer(FArchive& Ar, const FNiagaraSimCacheDataBuffers& Data, const FNiagaraSimCacheDataBuffersLayout& Layout)
    {
        uint32 Instances = Data.NumInstances, Tag = Data.IDAcquireTag, Ids = Data.IDToIndexTable.Num(), NextRows = Data.InterpMapping.Num();
        if (Data.FloatData.Num() != uint64(Instances) * Layout.FloatCount * sizeof(float)
            || Data.Int32Data.Num() != uint64(Instances) * Layout.Int32Count * sizeof(int32)
            || Data.HalfData.Num() != uint64(Instances) * Layout.HalfCount * sizeof(FFloat16)) return false;
        uint32 CaptureIds = 0;
        Ar << Instances << Tag << Ids << NextRows << CaptureIds;
        Ar.Serialize(Data.FloatData.GetData(), Data.FloatData.Num());
        Ar.Serialize(Data.Int32Data.GetData(), Data.Int32Data.Num());
        Ar.Serialize(Data.HalfData.GetData(), Data.HalfData.Num());
        for (int32 Id : Data.IDToIndexTable) Ar << Id;
        for (uint32 Row : Data.InterpMapping) Ar << Row;
        return !Ar.IsError();
    }
}

struct FNiagaraSimCacheExport
{
    static bool Write(const UNiagaraSimCache& Cache, const FString& Output)
    {
        if (!Cache.IsCacheValid() || Cache.CacheFrames.IsEmpty())
        {
            FBufferArchive Empty;
            uint32 Magic = 0x4353464c, Version = 0;
            Empty << Magic << Version;
            return !Empty.IsError() && FFileHelper::SaveArrayToFile(Empty, *Output);
        }
        auto Interfaces = Cache.GetStoredDataInterfaces();
        Interfaces.Sort([](const auto& A, const auto& B)
        {
            return A.GetName().LexicalLess(B.GetName());
        });
        TSet<FString> DynamicMaterials;
        for (const auto& Variable : Interfaces)
            if (const auto* Mesh = Cast<UNiagaraDataInterfaceDynamicMeshSimCacheData>(Cache.GetDataInterfaceStorageObject(Variable)))
                for (const auto& Frame : Mesh->UniqueFrames)
                    for (const auto& Section : Frame.Sections)
                        if (Section.Material) DynamicMaterials.Add(Section.Material->GetPathName());
        auto MaterialPaths = DynamicMaterials.Array();
        MaterialPaths.Sort();
        FBufferArchive Ar;
        const auto* Debug = Cache.GetDebugData();
        uint32 Magic = 0x4353464c, Version = 6, Emitters = Cache.CacheLayout.EmitterLayouts.Num(), Frames = Cache.CacheFrames.Num();
        float Start = Cache.GetStartSeconds(), Duration = Cache.GetDurationSeconds();
        const uint32 CaptureMode = uint32(Cache.GetAttributeCaptureMode()) + 1;
        uint32 Flags = (Cache.CreateParameters.bAllowInterpolation ? 1 : 0) | (Cache.CreateParameters.bAllowVelocityExtrapolation ? 2 : 0)
            | (CaptureMode << 2) | (Debug ? 16 : 0);
        Ar << Magic << Version;
        Text(Ar, Cache.GetSystemAsset().ToSoftObjectPath().ToString());
        Ar << Start << Duration << Flags << Emitters << Frames;
        int32 MaterialCount = MaterialPaths.Num();
        Ar << MaterialCount;
        for (const FString& Material : MaterialPaths) Text(Ar, Material);
        if (!Layout(Ar, Cache.CacheLayout.SystemLayout)) return false;
        for (const auto& Emitter : Cache.CacheLayout.EmitterLayouts)
            if (!Layout(Ar, Emitter)) return false;
        for (const auto& Frame : Cache.CacheFrames)
        {
            if (Frame.EmitterData.Num() != int32(Emitters)) return false;
            float Age = Frame.SimulationAge;
            int32 Tick = Frame.SimulationTickCount;
            Ar << Age << Tick;
            Vector(Ar, Frame.LocalToWorld.GetTranslation());
            const auto Rotation = Frame.LocalToWorld.GetRotation();
            double X = Rotation.X, Y = Rotation.Y, Z = Rotation.Z, W = Rotation.W;
            Ar << X << Y << Z << W;
            Vector(Ar, Frame.LocalToWorld.GetScale3D());
            Vector(Ar, Frame.LWCTile);
            Bounds(Ar, Frame.SystemData.LocalBounds);
            if (!Buffer(Ar, Frame.SystemData.SystemDataBuffers, Cache.CacheLayout.SystemLayout)) return false;
            for (int32 Index = 0; Index < Frame.EmitterData.Num(); ++Index)
            {
                const auto& Emitter = Frame.EmitterData[Index];
                Bounds(Ar, Emitter.LocalBounds);
                int32 Spawned = Emitter.TotalSpawnedParticles;
                Ar << Spawned;
                if (!Buffer(Ar, Emitter.ParticleDataBuffers, Cache.CacheLayout.EmitterLayouts[Index])) return false;
            }
        }
        int32 InterfaceCount = Interfaces.Num();
        Ar << InterfaceCount;
        for (const auto& Variable : Interfaces)
        {
            const UObject* Storage = Cache.GetDataInterfaceStorageObject(Variable);
            const bool bRebase = Cache.CreateParameters.bAllowRebasing
                && (Cache.CreateParameters.RebaseIncludeAttributes.Contains(Variable.GetName())
                    || !Cache.CreateParameters.RebaseExcludeAttributes.Contains(Variable.GetName()));
            if (!Storage || !Interface(Ar, Variable, *Storage, int32(Frames), bRebase))
            {
                UE_LOG(LogTemp, Error, TEXT("Cannot export Niagara cache storage for %ls in %ls"), *Variable.GetName().ToString(), *Cache.GetPathName());
                return false;
            }
        }
        if (Debug && !DebugData(Ar, *Debug, int32(Frames))) return false;
        return !Ar.IsError() && FFileHelper::SaveArrayToFile(Ar, *Output);
    }
};

INT32_MAIN_INT32_ARGC_TCHAR_ARGV()
{
    FTaskTagScope TaskScope(ETaskTag::EGameThread);
    ON_SCOPE_EXIT
    {
        RequestEngineExit(TEXT("Niagara simulation cache export completed"));
        FEngineLoop::AppPreExit();
        FModuleManager::Get().UnloadModulesAtShutdown();
        FEngineLoop::AppExit();
    };
    if (int32 Status = GEngineLoop.PreInit(ArgC, ArgV)) return Status;
    FString Input, Output, Json, Path;
    TSharedPtr<FJsonObject> Request;
    if (!FParse::Value(FCommandLine::Get(), TEXT("input="), Input)
        || !FParse::Value(FCommandLine::Get(), TEXT("output="), Output)
        || !FFileHelper::LoadFileToString(Json, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request)
        || !Request.IsValid()) return 2;
    bool bSparseVolume = Request->TryGetStringField(TEXT("sparse_volume"), Path);
    bool bVolumeCache = !bSparseVolume && Request->TryGetStringField(TEXT("volume_cache"), Path);
    bool bDataTable = !bSparseVolume && !bVolumeCache && Request->TryGetStringField(TEXT("data_table"), Path);
    if (!bSparseVolume && !bVolumeCache && !bDataTable && !Request->TryGetStringField(TEXT("cache"), Path)) return 2;
    const TSharedPtr<FJsonObject>* Roots = nullptr;
    if (!Request->TryGetObjectField(TEXT("mounts"), Roots)) return 3;
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    for (const auto& Root : (*Roots)->Values)
    {
        FString Directory;
        if (!Root.Key.StartsWith(TEXT("/")) || !Root.Key.EndsWith(TEXT("/")) || !Root.Value->TryGetString(Directory)
            || FPaths::IsRelative(Directory) || !FPaths::DirectoryExists(Directory)) return 3;
        FPaths::NormalizeDirectoryName(Directory);
        Mounts.Add(FPackageName::RegisterMountPoint(Root.Key, Directory + TEXT("/")));
    }
    if (bSparseVolume)
    {
        auto* Volume = LoadObject<USparseVolumeTexture>(nullptr, *Path);
        FBufferArchive Archive;
        return Volume && WriteLumenFxSparseVolumeAsset(Archive, *Volume) && FFileHelper::SaveArrayToFile(Archive, *Output) ? 0 : 4;
    }
    if (bVolumeCache)
    {
        auto* Cache = LoadObject<UVolumeCache>(nullptr, *Path);
        FString SavedDirectory = FPaths::ProjectSavedDir();
        if (Request->TryGetStringField(TEXT("volume_cache_saved_directory"), SavedDirectory))
        {
            if (FPaths::IsRelative(SavedDirectory) || !FPaths::DirectoryExists(SavedDirectory)) return 3;
            FPaths::NormalizeDirectoryName(SavedDirectory);
            SavedDirectory += TEXT("/");
        }
        FBufferArchive Archive;
        return Cache && WriteLumenFxVolumeCache(Archive, *Cache, SavedDirectory) && FFileHelper::SaveArrayToFile(Archive, *Output) ? 0 : 4;
    }
    if (bDataTable)
    {
        auto* Table = LoadObject<UDataTable>(nullptr, *Path);
        return Table && WriteLumenFxDataTable(*Table, Output) ? 0 : 4;
    }
    UNiagaraSimCache* Cache = LoadObject<UNiagaraSimCache>(nullptr, *Path);
    return Cache && FNiagaraSimCacheExport::Write(*Cache, Output) ? 0 : 4;
}
