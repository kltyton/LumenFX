#include "LumenFxDataTableExport.h"
#include "CoreMinimal.h"
#include "Dom/JsonObject.h"
#include "Engine/DataTable.h"
#include "Misc/FileHelper.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/UnrealType.h"

namespace
{
    struct FColumn
    {
        const FProperty* Property;
        FString Name;
        FString Type;
        int32 Width;
    };

    int32 FloatWord(float Value)
    {
        int32 Word;
        FMemory::Memcpy(&Word, &Value, sizeof(Word));
        return Word;
    }

    void AppendFloat(TArray<TSharedPtr<FJsonValue>>& Words, double Value)
    {
        Words.Add(MakeShared<FJsonValueNumber>(FloatWord(float(Value))));
    }
}

bool WriteLumenFxDataTable(const UDataTable& Table, const FString& Output)
{
    UScriptStruct* RowStruct = Table.RowStruct.Get();
    TArray<FColumn> Columns;
    for (FProperty* Property = RowStruct ? RowStruct->PropertyLink : nullptr; Property; Property = Property->PropertyLinkNext)
    {
        FString Type;
        int32 Width = 1;
        if (Property->IsA<FIntProperty>()) Type = TEXT("/Script/Niagara.NiagaraInt32");
        else if (Property->IsA<FFloatProperty>() || Property->IsA<FDoubleProperty>()) Type = TEXT("/Script/Niagara.NiagaraFloat");
        else if (const FStructProperty* Struct = CastField<FStructProperty>(Property))
        {
            if (Struct->Struct == TBaseStructure<FVector2D>::Get()) { Type = TEXT("/Script/CoreUObject.Vector2f"); Width = 2; }
            else if (Struct->Struct == TBaseStructure<FVector>::Get()) { Type = TEXT("/Script/CoreUObject.Vector3f"); Width = 3; }
            else if (Struct->Struct == TBaseStructure<FVector4>::Get()) { Type = TEXT("/Script/CoreUObject.Vector4f"); Width = 4; }
            else if (Struct->Struct == TBaseStructure<FQuat>::Get()) { Type = TEXT("/Script/CoreUObject.Quat4f"); Width = 4; }
            else if (Struct->Struct == TBaseStructure<FLinearColor>::Get()) { Type = TEXT("/Script/CoreUObject.LinearColor"); Width = 4; }
        }
        if (!Type.IsEmpty()) Columns.Add({Property, RowStruct->GetAuthoredNameForField(Property), MoveTemp(Type), Width});
    }

    TArray<TSharedPtr<FJsonValue>> ColumnJson;
    for (const FColumn& Column : Columns)
    {
        auto Value = MakeShared<FJsonObject>();
        Value->SetStringField(TEXT("name"), Column.Name);
        Value->SetStringField(TEXT("type"), Column.Type);
        Value->SetNumberField(TEXT("width"), Column.Width);
        ColumnJson.Add(MakeShared<FJsonValueObject>(Value));
    }
    TArray<TSharedPtr<FJsonValue>> Rows;
    for (const auto& Pair : Table.GetRowMap())
    {
        if (!RowStruct) break;
        if (!Pair.Value) return false;
        TArray<TSharedPtr<FJsonValue>> Words;
        for (const FColumn& Column : Columns)
        {
            const uint8* Value = Pair.Value + Column.Property->GetOffset_ForInternal();
            if (Column.Property->IsA<FIntProperty>())
                Words.Add(MakeShared<FJsonValueNumber>(static_cast<const FIntProperty*>(Column.Property)->GetPropertyValue(Value)));
            else if (Column.Property->IsA<FFloatProperty>()) AppendFloat(Words, static_cast<const FFloatProperty*>(Column.Property)->GetPropertyValue(Value));
            else if (Column.Property->IsA<FDoubleProperty>()) AppendFloat(Words, static_cast<const FDoubleProperty*>(Column.Property)->GetPropertyValue(Value));
            else if (Column.Type.EndsWith(TEXT("Vector2f")))
            { const FVector2D& V = *reinterpret_cast<const FVector2D*>(Value); AppendFloat(Words, V.X); AppendFloat(Words, V.Y); }
            else if (Column.Type.EndsWith(TEXT("Vector3f")))
            { const FVector& V = *reinterpret_cast<const FVector*>(Value); AppendFloat(Words, V.X); AppendFloat(Words, V.Y); AppendFloat(Words, V.Z); }
            else if (Column.Type.EndsWith(TEXT("Vector4f")))
            { const FVector4& V = *reinterpret_cast<const FVector4*>(Value); AppendFloat(Words, V.X); AppendFloat(Words, V.Y); AppendFloat(Words, V.Z); AppendFloat(Words, V.W); }
            else if (Column.Type.EndsWith(TEXT("Quat4f")))
            { const FQuat& V = *reinterpret_cast<const FQuat*>(Value); AppendFloat(Words, V.X); AppendFloat(Words, V.Y); AppendFloat(Words, V.Z); AppendFloat(Words, V.W); }
            else
            { const FLinearColor& V = *reinterpret_cast<const FLinearColor*>(Value); AppendFloat(Words, V.R); AppendFloat(Words, V.G); AppendFloat(Words, V.B); AppendFloat(Words, V.A); }
        }
        auto Row = MakeShared<FJsonObject>();
        Row->SetStringField(TEXT("name"), Pair.Key.ToString());
        Row->SetArrayField(TEXT("words"), MoveTemp(Words));
        Rows.Add(MakeShared<FJsonValueObject>(Row));
    }
    auto Root = MakeShared<FJsonObject>();
    Root->SetStringField(TEXT("source"), Table.GetPathName());
    Root->SetArrayField(TEXT("columns"), MoveTemp(ColumnJson));
    Root->SetArrayField(TEXT("rows"), MoveTemp(Rows));
    FString Text;
    auto Writer = TJsonWriterFactory<>::Create(&Text);
    return FJsonSerializer::Serialize(Root, Writer)
        && FFileHelper::SaveStringToFile(Text, *Output, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);
}
