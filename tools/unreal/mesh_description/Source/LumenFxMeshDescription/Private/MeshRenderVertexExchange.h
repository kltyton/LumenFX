#pragma once

class FArchive;
struct FStaticMeshVertexBuffers;

bool WriteRenderVertexChannels(FArchive& Ar, const FStaticMeshVertexBuffers& Buffers);
