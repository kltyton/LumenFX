using History = LumenFx.Compiler.Unreal.Serialization.UnrealTextHistory;
using Argument = LumenFx.Compiler.Unreal.Serialization.UnrealTextArgument;

namespace LumenFx.Compiler.Unreal.Serialization;

// Binary FText history fields from the fixed UE 5.8.4 source. Graph pins use only
// their non-text defaults, but their two FText values must be consumed in full.
internal sealed class UnrealTextHistoryReader(UnrealPackageBinary archive)
{
    private const int CultureInvariantTextSerializationKeyStability = 32;
    private const int AddedAlwaysSignNumberFormattingOption = 21;
    private const int TextFormatArgumentDataIsVariant = 5;
    private const int TextFormatArgumentData64bitSupport = 12;
    private const int AddDevNotesToFText = 260;
    private const byte CustomDateStyle = 5;
    private readonly int editorVersion = archive.CustomVersion("{E4B068ED-F494-42E9-A231-DA0B2E46BB41}");
    private readonly int releaseVersion = archive.CustomVersion("{D89B5E42-24BD-4D46-8412-ACA8DF641779}");
    private readonly bool developerNotes = !archive.FiltersEditorOnly
        && archive.CustomVersion("{601D1886-AC64-4F84-AA16-D3DE0DEAC7D6}") >= AddDevNotesToFText;

    internal void Skip() => Skip(0);

    private void Skip(int depth)
    {
        if (depth == 128) throw new ConversionException("Unreal text history nesting exceeds the supported archive depth");
        archive.Skip(sizeof(uint)); // FText flags
        var history = (History)archive.Reader.ReadSByte();
        switch (history) {
            case History.None:
                if (editorVersion >= CultureInvariantTextSerializationKeyStability && archive.Boolean()) archive.String();
                break;
            case History.Base:
                archive.String(); // Namespace
                archive.String(); // Key
                archive.String(); // Source string
                if (developerNotes) archive.String();
                break;
            case History.NamedFormat:
            case History.OrderedFormat:
            case History.ArgumentFormat:
                Skip(depth + 1);
                int arguments = archive.Count(1048576);
                for (int i = 0; i < arguments; i++) {
                    if (history != History.OrderedFormat) archive.String();
                    SkipArgument(history == History.ArgumentFormat, depth);
                }
                break;
            case History.AsNumber:
            case History.AsPercent:
            case History.AsCurrency:
                if (history == History.AsCurrency) archive.String();
                SkipArgument(false, depth);
                if (archive.Boolean()) {
                    if (editorVersion >= AddedAlwaysSignNumberFormattingOption) archive.Boolean();
                    archive.Boolean(); // UseGrouping
                    archive.Skip(sizeof(byte) + 4 * sizeof(int));
                }
                archive.String(); // Target culture
                break;
            case History.AsDate:
            case History.AsTime:
            case History.AsDateTime:
                archive.Skip(sizeof(long)); // FDateTime ticks
                byte style = archive.Reader.ReadByte();
                if (history == History.AsDateTime) {
                    archive.Skip(sizeof(byte)); // Time style
                    if (style == CustomDateStyle) archive.String();
                }
                archive.String(); // Time zone
                archive.String(); // Target culture
                break;
            case History.Transform:
                Skip(depth + 1);
                archive.Skip(sizeof(byte));
                break;
            case History.StringTableEntry:
                archive.Name();
                archive.String();
                break;
            case History.TextGenerator:
                if (!string.Equals(archive.Name(), "None", StringComparison.OrdinalIgnoreCase))
                    archive.Skip(archive.Count(int.MaxValue));
                break;
            default:
                throw new ConversionException($"Unknown Unreal text history tag {(sbyte)history}");
        }
    }

    private void SkipArgument(bool argumentData, int depth)
    {
        var type = argumentData && editorVersion < TextFormatArgumentDataIsVariant
            ? Argument.Text : (Argument)archive.Reader.ReadByte();
        switch (type) {
            case Argument.Int:
                archive.Skip(argumentData && releaseVersion < TextFormatArgumentData64bitSupport ? sizeof(int) : sizeof(long));
                break;
            case Argument.UInt when !argumentData:
            case Argument.Double:
                archive.Skip(sizeof(long));
                break;
            case Argument.UInt:
                break; // FFormatArgumentData has no UInt payload.
            case Argument.Float:
                archive.Skip(sizeof(float));
                break;
            case Argument.Text:
                Skip(depth + 1);
                break;
            case Argument.Gender:
                archive.Skip(argumentData ? sizeof(byte) : sizeof(ulong));
                break;
            default:
                throw new ConversionException($"Unknown Unreal text argument tag {(byte)type}");
        }
    }
}
