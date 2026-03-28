package my.pkg;

import org.bukkit.Material;

public record AbilityInfo(
        String id,
        String name,
        String summary,
        Material icon
) {}