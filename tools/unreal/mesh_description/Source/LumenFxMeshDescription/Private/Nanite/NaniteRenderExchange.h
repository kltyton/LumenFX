#pragma once

class FArchive;
class ITargetPlatform;
class UStaticMesh;
namespace Nanite { struct FResources; }

bool WriteNaniteRenderExchange(FArchive& Output, Nanite::FResources* Resources, UStaticMesh& Owner, const ITargetPlatform& Platform);
