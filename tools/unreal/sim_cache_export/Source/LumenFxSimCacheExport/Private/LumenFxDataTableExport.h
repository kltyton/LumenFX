#pragma once

class UDataTable;

// Export the actual reflected row schema and loaded row values in Niagara's layout.
bool WriteLumenFxDataTable(const UDataTable& Table, const FString& Output);
