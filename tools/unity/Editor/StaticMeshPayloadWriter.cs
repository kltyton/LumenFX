using System;
using System.IO;
using System.Text;

namespace LumenFx.Geometry
{
    // Shared by the Unity editor tool and the offline Unreal compiler.
    internal static class StaticMeshPayloadWriter
    {
        internal static byte[] Write(int vertexIdKind, int[] vertexIds, int[] indices, int uvChannels,
            int sections, Action<BinaryWriter> writeVertexStreams, Action<BinaryWriter> writeSections,
            int[] uvWidths = null)
        {
            bool wideUv = false;
            if (uvWidths != null)
            {
                for (int i = 0; i < uvWidths.Length; i++)
                    if (uvWidths[i] > 2) { wideUv = true; break; }
            }
            using (var output = new MemoryStream())
            {
                using (var writer = new BinaryWriter(output, Encoding.UTF8, true))
                {
                    writer.Write(0x484d464c);
                    writer.Write(wideUv ? 3 : vertexIdKind == 0 ? 1 : 2);
                    if (wideUv || vertexIdKind != 0) writer.Write(vertexIdKind);
                    writer.Write(vertexIds.Length);
                    writer.Write(indices.Length);
                    writer.Write(uvChannels);
                    writer.Write(sections);
                    if (wideUv)
                    {
                        for (int i = 0; i < uvChannels; i++)
                            writer.Write(uvWidths != null && i < uvWidths.Length && uvWidths[i] > 2 ? 4 : 2);
                    }
                    foreach (int id in vertexIds) writer.Write(id);
                    writeVertexStreams(writer);
                    foreach (int index in indices) writer.Write(index);
                    writeSections(writer);
                }
                return output.ToArray();
            }
        }

        internal static void WriteSection(BinaryWriter writer, int group, string material, int firstIndex, int indexCount)
        {
            writer.Write(group);
            byte[] name = Encoding.UTF8.GetBytes(material);
            writer.Write(name.Length);
            writer.Write(name);
            writer.Write(firstIndex);
            writer.Write(indexCount);
        }
    }
}
