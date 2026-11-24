namespace LumenFx.Compiler.Unreal.Serialization;

internal enum UnrealTextHistory : sbyte
{
    None = -1, Base, NamedFormat, OrderedFormat, ArgumentFormat, AsNumber, AsPercent, AsCurrency,
    AsDate, AsTime, AsDateTime, Transform, StringTableEntry, TextGenerator
}

internal enum UnrealTextArgument : byte { Int, UInt, Float, Double, Text, Gender }
